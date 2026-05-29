package orca.plan

import orca.{FlowContext, OrcaFlowException}
import orca.llm.{Announce, BackendTag, CanAskUser, JsonData, LlmTool, given}
import orca.events.OrcaEvent

/** A development plan: an ordered list of [[Task]]s the agent will work
  * through, all on a single branch named by `epicId` (kebab-case, used directly
  * as the git branch name).
  *
  * ==Planning grid==
  *
  * The planning entry points form a `mode × operation` grid. Mode and operation
  * are orthogonal — every combination is valid:
  *
  *   - **mode** — [[autonomous]] (single agentic turn, read-only, no human) or
  *     [[interactive]] (a conversation the agent can drive via `ask_user`).
  *     Chosen by the nested object so it's visible at the call site, mirroring
  *     `claude.autonomous.run` / `claude.resultAs[O].interactive.run`.
  *   - **operation** — `from` (produce a [[Plan]] directly), `assessThenPlan`
  *     (skeptically assess first, returning a [[Verdict]] that either proceeds
  *     with a plan or rejects), or `triage` (classify a bug report into a
  *     [[Triage]] verdict).
  *
  * Every cell returns a [[Sessioned]] — the result plus the agent session that
  * produced it, so the caller can continue that session into implementation or
  * discard it and mint a fresh one.
  *
  * ==Persistence==
  *
  * [[recoverOrCreate]] + [[persistComplete]] + [[implementTaskLoop]] round-trip
  * a plan through a `## Task: …` markdown file (parsed/rendered by [[parse]] /
  * [[render]]) so a flow that crashes mid-loop resumes without re-planning.
  *
  * `derives JsonData` so the structured-output path works directly: the helper
  * methods consume Orca's auto-generated JSON schema; no caller-side
  * serialization is needed.
  */
case class Plan(
    epicId: String,
    description: String,
    tasks: List[Task]
) derives JsonData:

  /** Mark the task with the given `title` complete, leaving the others
    * untouched. Returns the same plan if no task matches.
    */
  def markComplete(title: Title): Plan =
    copy(tasks = tasks.map(t => if t.title == title then t.markComplete else t))

  /** First task whose `completed` flag is false, in declaration order. `None`
    * means the plan is fully done.
    */
  def firstIncomplete: Option[Task] = tasks.find(!_.completed)

object Plan:

  /** Subdirectory under `workDir` where persistent plans live. Hidden so plain
    * `ls` doesn't show it; convention rather than configuration to keep
    * resume-after-crash predictable across flows.
    */
  val DefaultDir: os.SubPath = os.sub / ".orca"

  /** Default path for a persistent plan. `<workDir>/.orca/plan-<hash>.md` where
    * `<hash>` is the first 12 hex chars of SHA-256(userPrompt). Two unrelated
    * prompts in the same repo get different files; rerunning the same prompt
    * resumes the same plan.
    */
  def defaultPath(userPrompt: String, workDir: os.Path = os.pwd): os.Path =
    workDir / DefaultDir / s"plan-${hashUserPrompt(userPrompt)}.md"

  /** First 6 bytes of SHA-256(userPrompt) rendered as 12 hex chars. Visible for
    * testing; flow scripts should go through [[defaultPath]].
    */
  private[plan] def hashUserPrompt(userPrompt: String): String =
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val digest = md.digest(userPrompt.getBytes("UTF-8"))
    digest.iterator.take(6).map(b => f"${b & 0xff}%02x").mkString

  /** Autonomous planning — a single agentic turn, no human in the loop. The
    * agent runs read-only (`.withReadOnly`), so it can verify claims via
    * Read/Grep but can't edit during the planning turn. Sibling of
    * [[interactive]]; the choice between the two is visible at the call site
    * (`Plan.autonomous.from(...)` vs `Plan.interactive.from(...)`), mirroring
    * `LlmTool`'s own `autonomous` / `interactive` split.
    *
    * Each operation returns a [[Sessioned]]: the read-only planning turn's
    * session is still resumable by a later writable call, so the caller can
    * continue it into implementation or discard it for a fresh session.
    *
    * No `CanAskUser` bound (unlike [[interactive]]): an autonomous turn never
    * calls `ask_user`, so it works with any backend.
    */
  object autonomous:
    /** Produce a [[Plan]] directly from `userPrompt`. */
    def from[B <: BackendTag](
        userPrompt: String,
        llm: LlmTool[B],
        instructions: String = PlanPrompts.Planning
    )(using FlowContext): Sessioned[B, Plan] =
      autonomousResult[B, Plan, Plan](llm, userPrompt, instructions)(identity)

    /** Skeptically assess `userPrompt` (typically a bug/feature report) and
      * either proceed with a plan or reject with a [[Verdict.Rejection]] the
      * caller surfaces to whoever filed it.
      */
    def assessThenPlan[B <: BackendTag](
        userPrompt: String,
        llm: LlmTool[B],
        instructions: String = PlanPrompts.AssessThenPlan
    )(using FlowContext): Sessioned[B, Verdict[Plan]] =
      autonomousResult[B, AssessedPlan, Verdict[Plan]](
        llm,
        userPrompt,
        instructions
      )(a => getOrFail(a.toVerdict))

    /** Classify a bug report into a [[Triage]] verdict (not-a-bug / untestable
      * / testable).
      */
    def triage[B <: BackendTag](
        report: String,
        llm: LlmTool[B],
        instructions: String = PlanPrompts.Triage
    )(using FlowContext): Sessioned[B, Triage] =
      autonomousResult[B, BugTriage, Triage](llm, report, instructions)(b =>
        getOrFail(b.toTriage)
      )

    /** Persistence convenience: parse `file` if it exists (resume), else
      * generate via [[from]] and persist. Returns a bare [[Plan]] — unlike the
      * grid operations above — because the resume branch has no session to
      * expose. The implementer mints its own session.
      */
    def loadOrGenerate[B <: BackendTag](
        file: os.Path,
        userPrompt: String,
        llm: LlmTool[B],
        instructions: String = PlanPrompts.Planning
    )(using FlowContext): Plan =
      loadOrGenerateImpl(file, () => from(userPrompt, llm, instructions).value)

  /** Interactive planning — the LLM call opens a conversation the user can
    * drive (clarifying questions, refinements) before the agent produces the
    * result. Not read-only: claude's plan mode would disable the `ask_user` MCP
    * tool, so the agent runs with normal permissions; the prompt asks it not to
    * edit, and the user sees any violation.
    *
    * The `B: CanAskUser` constraint means these compile only with backends that
    * can host an `ask_user` tool — claude and codex (both via the shared
    * `AskUserMcpServer`). A future stdin-only backend would fail this at
    * compile time. Use [[autonomous]] when no mid-session questions are needed.
    *
    * Each operation returns a [[Sessioned]] so the conversation can carry into
    * implementation (e.g. a triage agent's exploration informs the fix).
    */
  object interactive:
    /** Produce a [[Plan]] directly from `userPrompt`. */
    def from[B <: BackendTag: CanAskUser](
        userPrompt: String,
        llm: LlmTool[B],
        instructions: String = PlanPrompts.Planning
    )(using FlowContext): Sessioned[B, Plan] =
      interactiveResult[B, Plan, Plan](llm, userPrompt, instructions)(identity)

    /** Skeptically assess `userPrompt`, but able to ask the reporter clarifying
      * questions mid-turn rather than only rejecting with a
      * [[Verdict.RejectionKind.Question]].
      */
    def assessThenPlan[B <: BackendTag: CanAskUser](
        userPrompt: String,
        llm: LlmTool[B],
        instructions: String = PlanPrompts.AssessThenPlan
    )(using FlowContext): Sessioned[B, Verdict[Plan]] =
      interactiveResult[B, AssessedPlan, Verdict[Plan]](
        llm,
        userPrompt,
        instructions
      )(a => getOrFail(a.toVerdict))

    /** Classify a bug report into a [[Triage]] verdict, able to ask the
      * reporter clarifying questions before deciding.
      */
    def triage[B <: BackendTag: CanAskUser](
        report: String,
        llm: LlmTool[B],
        instructions: String = PlanPrompts.Triage
    )(using FlowContext): Sessioned[B, Triage] =
      interactiveResult[B, BugTriage, Triage](llm, report, instructions)(b =>
        getOrFail(b.toTriage)
      )

    /** Persistence convenience: parse `file` if it exists (resume), else
      * generate via [[from]] and persist. Returns a bare [[Plan]] — see the
      * autonomous counterpart for why no session is exposed.
      */
    def loadOrGenerate[B <: BackendTag: CanAskUser](
        file: os.Path,
        userPrompt: String,
        llm: LlmTool[B],
        instructions: String = PlanPrompts.Planning
    )(using FlowContext): Plan =
      loadOrGenerateImpl(file, () => from(userPrompt, llm, instructions).value)

  /** Append the operation's instruction block to the caller's input. */
  private def withInstructions(input: String, instructions: String): String =
    s"$input\n\n$instructions"

  /** Surface a structured-contract violation (a `toVerdict` / `toTriage`
    * `Left`) as a flow failure. The decode succeeded but the field combination
    * was incoherent past the retry loop, so it's a system-level failure.
    */
  private def getOrFail[A](result: Either[String, A]): A =
    result.fold(msg => throw OrcaFlowException(msg), identity)

  /** Run one read-only autonomous turn producing wire type `O`, convert it to
    * the public result `A`, and pair it with the session. Shared by every
    * `autonomous.*` operation.
    */
  private def autonomousResult[B <: BackendTag, O: JsonData: Announce, A](
      llm: LlmTool[B],
      input: String,
      instructions: String
  )(convert: O => A)(using FlowContext): Sessioned[B, A] =
    val (sessionId, raw) = llm.withReadOnly
      .resultAs[O]
      .autonomous
      .run(withInstructions(input, instructions))
    Sessioned(sessionId, convert(raw))

  /** Interactive counterpart to [[autonomousResult]]. */
  private def interactiveResult[
      B <: BackendTag: CanAskUser,
      O: JsonData: Announce,
      A
  ](
      llm: LlmTool[B],
      input: String,
      instructions: String
  )(convert: O => A)(using FlowContext): Sessioned[B, A] =
    val (sessionId, raw) =
      llm.resultAs[O].interactive.run(withInstructions(input, instructions))
    Sessioned(sessionId, convert(raw))

  /** Common load-or-generate body: read+log on resume, otherwise call
    * `generate` and persist its result. The two public variants differ only in
    * which LLM-call shape they pass for `generate` (interactive vs autonomous).
    */
  private def loadOrGenerateImpl(file: os.Path, generate: () => Plan)(using
      ctx: FlowContext
  ): Plan =
    if os.exists(file) then
      val parsed = parse(os.read(file))
      ctx.emit(
        OrcaEvent.Step(
          s"Reusing existing plan at $file (${parsed.tasks.size} task(s), ${parsed.tasks.count(_.completed)} already complete)"
        )
      )
      parsed
    else
      val plan = generate()
      os.write.over(file, render(plan), createFolders = true)
      plan

  /** Empty plans render as nothing — surfacing "0 tasks planned" muddies the
    * picture; a planning failure is more useful as an explicit `fail(...)` from
    * the script.
    */
  given Announce[Plan] = Announce.from: plan =>
    if plan.tasks.isEmpty then ""
    else
      val plural = if plan.tasks.size == 1 then "" else "s"
      val header =
        s"Planned ${plan.tasks.size} task$plural on branch '${plan.epicId}':"
      val body = plan.tasks.map(t => s"  - ${t.title}").mkString("\n")
      s"$header\n$body"

  /** Mark the task with `title` complete in the plan stored at `file`. Reads
    * the file, applies the change, writes it back. Use after a task is
    * committed so a subsequent run resumes at the next pending task.
    */
  def persistComplete(file: os.Path, title: Title): Unit =
    val current = parse(os.read(file))
    val updated = current.markComplete(title)
    os.write.over(file, render(updated))

  /** Acquire a persistent plan: resume from `file` if it exists, otherwise
    * evaluate `generate` (typically `Plan.autonomous.from(...).value`) and lay
    * down the branch + on-disk plan for a fresh run.
    *
    * `generate` is a bare `=> Plan`, not a `Sessioned`: on the resume branch
    * there's no planning session to carry, so this helper can't expose one
    * consistently. Callers that want session reuse should drive the grid
    * operation directly instead of through `recoverOrCreate`; flows here mint a
    * fresh implementer session via `llm.newSession` (so `.value` the planning
    * result).
    *
    * `stashMessage` is used when a fresh start finds a dirty tree; pass a
    * flow-specific string so `git stash list` is searchable.
    */
  def recoverOrCreate(
      file: os.Path,
      stashMessage: String = "orca: starting work"
  )(generate: => Plan)(using ctx: FlowContext): Plan =
    recover(file).getOrElse:
      // ensureClean *before* generate so the planner sees a known-clean
      // tree (and the "stashed pending changes" Step only fires when the
      // user actually had pre-existing dirty edits, not when the planner
      // itself wrote files — `Plan.autonomous.from` runs read-only).
      val _ = ctx.git.ensureClean(stashMessage)
      val plan = generate
      ctx.git.checkoutOrCreate(plan.epicId)
      os.write.over(file, render(plan), createFolders = true)
      plan

  /** Resume from a previously-persisted plan. Returns `Some(plan)` when `file`
    * exists, with the working tree cleaned (any pending edits stashed; the user
    * can `git stash pop` afterwards) and the working copy attached to
    * `plan.epicId`. Returns `None` when no file exists — the caller decides
    * whether to generate a fresh plan ([[recoverOrCreate]] does that
    * automatically) or treat the absence as a hard failure.
    */
  def recover(file: os.Path)(using ctx: FlowContext): Option[Plan] =
    if !os.exists(file) then None
    else
      // Snapshot the file before stashing so an untracked plan file (one
      // created on disk but never committed — the common crash-before-first-
      // task-commit case) survives `ensureClean -u`. After the stash, if the
      // file is gone, restore it from the snapshot. If it's still there,
      // we trust the post-stash version — a tracked+dirty plan file should
      // resume from the committed contents, not the in-progress edits.
      val snapshot = os.read(file)
      val _ = ctx.git.ensureClean("orca: pre-recovery stash")
      val source =
        if os.exists(file) then os.read(file)
        else
          os.write.over(file, snapshot, createFolders = true)
          snapshot
      val plan = parse(source)
      ctx.git.checkoutOrCreate(plan.epicId)
      ctx.emit(
        OrcaEvent.Step(
          s"Recovered plan at $file (${plan.tasks.size} task(s), ${plan.tasks.count(_.completed)} already complete)"
        )
      )
      Some(plan)

  /** Per-task implementation loop with on-disk progress + commits.
    *
    * For each incomplete task in `plan`:
    *
    *   1. Calls `body(task)` — the caller's implement-and-review work. 2. Ticks
    *      the task's `Status: [x]` in `file`. 3. Makes one `task: <title>` git
    *      commit covering both the body's changes and the checkbox tick.
    *
    * After the last task: removes `file` and makes a `chore: remove
    * <file.last>` cleanup commit (skipped if the file was never tracked).
    * Bodies that throw abort the loop with the partial plan still on disk, so a
    * subsequent run resumes at the first incomplete task.
    *
    * Sibling of [[orca.review.reviewAndFixLoop]] — that one drives the
    * review-and-fix iteration within a task; this one drives task-by-task
    * progress across the whole plan.
    */
  def implementTaskLoop(file: os.Path, plan: Plan)(body: Task => Unit)(using
      ctx: FlowContext
  ): Unit =
    runTaskLoop(
      initial = plan,
      advance = (_, t) =>
        persistComplete(file, t.title)
        val next = parse(os.read(file))
        // Defense in depth: persistComplete + a clean re-read should always
        // advance past the just-processed title. If it didn't, the on-disk plan
        // diverged from what we just wrote — surface it instead of spinning.
        if next.firstIncomplete.map(_.title).contains(t.title) then
          throw OrcaFlowException(
            s"implementTaskLoop: task '${t.title.value}' is still the first incomplete entry " +
              s"after persistComplete. The plan file at $file may have been " +
              "edited so the title still matches an unchecked task."
          )
        next
      ,
      // Cleanup commit only fires if there's actually a file to remove —
      // skipping the no-op branch avoids a wasted `git add -A` subprocess and
      // a misleading "chore: remove …" commit-message intent when the file
      // never existed (e.g. caller pre-removed it).
      cleanup = () =>
        if os.exists(file) then
          val _ = os.remove(file)
          // `NothingToCommit` swallowed so a `.gitignore`d plan dir (untracked
          // file → no diff after removal) doesn't crash the whole run.
          val _ = ctx.git.commit(s"chore: remove ${file.last}")
    )(body)

  /** In-memory variant of [[implementTaskLoop]] for flows that aren't
    * resumable: same per-task `task: <title>` commit cadence, but completion is
    * tracked in memory (no plan file, no chore-remove commit). Use when the
    * surrounding flow has its own non-restartable state machine and a
    * `.orca/plan-*.md` file would just be dead weight (e.g. a bugfix flow whose
    * earlier stages — triage, CI red, repro verification — can't be resumed
    * from a plan-file alone).
    */
  def implementTaskLoop(plan: Plan)(body: Task => Unit)(using
      FlowContext
  ): Unit =
    runTaskLoop(
      initial = plan,
      advance = (cur, t) => cur.markComplete(t.title),
      cleanup = () => ()
    )(body)

  private def runTaskLoop(
      initial: Plan,
      advance: (Plan, Task) => Plan,
      cleanup: () => Unit
  )(body: Task => Unit)(using ctx: FlowContext): Unit =
    @scala.annotation.tailrec
    def loop(current: Plan): Unit = current.firstIncomplete match
      case None => ()
      case Some(t) =>
        body(t)
        val next = advance(current, t)
        // `NothingToCommit` is non-fatal here: a body that produced only
        // gitignored output (the plan-file tick when `.orca/` is in
        // `.gitignore`, or a no-op task by design) shouldn't abort the
        // loop and leave the next run skipping a task on the strength of
        // an on-disk tick alone. Same swallow the cleanup commit already
        // does. Surface the swallow as a Step so a body that silently
        // produced nothing (e.g. an LLM turn that ended without edits)
        // is still visible in the event log.
        ctx.git.commit(s"task: ${t.title}") match
          case Right(_) => ()
          case Left(_) =>
            ctx.emit(
              OrcaEvent.Step(
                s"task '${t.title.value}' produced no tracked changes — advancing without commit"
              )
            )
        loop(next)
    loop(initial)
    cleanup()

  /** Parse a plan from its markdown representation. Strict — throws
    * [[PlanParseException]] on any deviation from the schema. CRLF line endings
    * and a leading BOM are normalised first.
    */
  def parse(markdown: String): Plan =
    val normalised = markdown.stripPrefix("\uFEFF").replace("\r\n", "\n")
    val lines = normalised.linesIterator.toList
    val epicId = parseHeader(lines)
    val description = parseDescription(lines)
    val taskBlocks = splitTaskBlocks(lines)
    if taskBlocks.isEmpty then throw PlanParseException("Plan has no tasks")
    Plan(epicId, description, taskBlocks.map(parseTask))

  /** Render a plan back into the on-disk format. Output round-trips through
    * [[parse]] without information loss; we use this to write the file when
    * first generated and again when a task's status flips.
    */
  def render(plan: Plan): String =
    val header = s"# Plan: ${plan.epicId}\n"
    val descriptionBlock =
      if plan.description.trim.isEmpty then ""
      else s"\n${plan.description.stripLineEnd}\n"
    val body = plan.tasks
      .map: t =>
        val checkbox = if t.completed then "[x]" else "[ ]"
        s"\n## Task: ${t.title}\nStatus: $checkbox\n\n${t.description.stripLineEnd}\n"
      .mkString
    header + descriptionBlock + body

  // --- Parser internals ---

  private val HeaderPattern = "^# Plan:\\s*(\\S.*)$".r
  private val TaskHeaderPattern = "^## Task:\\s*(\\S.*)$".r
  private val StatusPattern = "^Status:\\s*\\[(.)\\]\\s*$".r

  private def parseHeader(lines: List[String]): String =
    lines.find(_.trim.nonEmpty) match
      case Some(HeaderPattern(id)) => id.trim
      case other =>
        throw PlanParseException(
          s"Expected first non-blank line to match `# Plan: <epicId>`; got: ${other.getOrElse("(empty file)")}"
        )

  /** Description sits between the `# Plan:` header and the first `## Task:`
    * heading. Empty when the file goes straight from the header into tasks.
    */
  private def parseDescription(lines: List[String]): String =
    val afterHeader = lines.dropWhile(l => !HeaderPattern.matches(l)).drop(1)
    afterHeader
      .takeWhile(l => !TaskHeaderPattern.matches(l))
      .mkString("\n")
      .trim

  private def splitTaskBlocks(lines: List[String]): List[List[String]] =
    val blocks = collection.mutable.ListBuffer[List[String]]()
    var current = collection.mutable.ListBuffer[String]()
    var inTask = false
    for line <- lines do
      if TaskHeaderPattern.matches(line) then
        if inTask then blocks += current.toList
        current = collection.mutable.ListBuffer(line)
        inTask = true
      else if inTask then current += line
    if inTask then blocks += current.toList
    blocks.toList

  private def parseTask(block: List[String]): Task =
    val title = block.headOption match
      case Some(TaskHeaderPattern(t)) => t.trim
      case _ =>
        throw PlanParseException(
          s"Task block doesn't start with `## Task: <title>`: ${block.headOption.getOrElse("")}"
        )
    val rest = block.tail.dropWhile(_.trim.isEmpty)
    val (statusLine, afterStatus) = rest.headOption match
      case Some(line @ StatusPattern(_)) => (line, rest.tail)
      case _ =>
        throw PlanParseException(
          s"Task '$title' is missing a `Status: [ ]` / `Status: [x]` line"
        )
    val completed = statusLine match
      case StatusPattern(" ") => false
      case StatusPattern("x") => true
      case StatusPattern(other) =>
        throw PlanParseException(
          s"Task '$title' has unrecognised status checkbox '$other'"
        )
    val description = afterStatus.mkString("\n").trim
    if description.isEmpty then
      throw PlanParseException(s"Task '$title' has no prompt body")
    Task(title = Title(title), description = description, completed = completed)

class PlanParseException(message: String) extends RuntimeException(message)
