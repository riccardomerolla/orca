//> using dep "org.virtuslab::orca:0.0.3"
//> using jvm 21

/** Run an epic: a multi-task workstream with cross-agent review.
  *
  * Two layers stack here:
  *
  *   - **On-disk epic.** `.orca/plan-<hash>.md` holds the task list; on a
  *     fresh run the agent generates it, on a resume the existing file is
  *     recovered (pending edits stashed, branch re-attached) and execution
  *     restarts from the first incomplete task. Each task's `Status: [x]`
  *     checkbox is committed back to the plan file as the task lands, so a
  *     crash mid-flow loses no progress.
  *   - **Cross-agent review.** Claude implements; codex reviews. The
  *     implementing agent is its own worst critic — running reviewers on a
  *     separate model widens coverage without much extra cost. Fixes go back
  *     to the same Claude session. Both CLIs need to be logged in.
  *
  * At the end of a successful run the plan file is removed, then the
  * documentation step updates the project README based on what changed.
  *
  * Lives alongside the seeded todo-cli project so a user can run it from the
  * project's root after `examples/04-epic/create-test-project.sh`:
  *
  * ```bash
  * scala-cli run epic.sc -- \
  *   "Persist tasks to a JSON file at ~/.todo/tasks.json (load on startup, save on every change), \
  *    add 'done <id>' and 'delete <id>' commands, and support priority levels (low/medium/high) \
  *    with a 'list --priority' filter"
  * ```
  */

import orca.{*, given}

flow(OrcaArgs(args)):
  val planFile = Plan.defaultPath(userPrompt)

  // Resume `.orca/plan-<hash>.md` if it exists; otherwise plan + branch.
  val plan = stage("Acquire epic"):
    Plan.recoverOrCreate(planFile, "orca: starting epic"):
      Plan.autonomous.from(userPrompt, claude.opus)._2

  // System prompt covers the whole epic run — the runtime owns commits, so
  // the agent must never invoke git itself (a stray `git commit` would empty
  // the working tree and crash the next `runPersistent` commit step).
  val coder = claude.withSystemPrompt(
    "The runtime handles git commits. Never run `git commit` yourself."
  )

  // Reviewers on codex (not claude — the implementer is its own worst critic);
  // fixes go back to the same Claude session that implemented the task.
  val reviewers: List[LlmTool[?]] = allReviewers(codex)

  // One coder session across tasks so the docs pass below can see the whole
  // epic's context. Lazy: started by the first task, reused thereafter.
  var sessionId: Option[SessionId[BackendTag.ClaudeCode.type]] = None

  Plan.runPersistent(planFile, plan): task =>
    stage(s"Implement task: ${task.title}"):
      val sid = stage("Implementation"):
        sessionId match
          case Some(s) =>
            val _ = coder.autonomous.continueSession(s, task.description)
            s
          case None =>
            val (fresh, _) = coder.autonomous.startSession(task.description)
            sessionId = Some(fresh)
            fresh

      // Format before review so reviewers don't burn turns on style nits the
      // toolchain would fix automatically. Spotless is wired into the seed pom.
      stage("Format"):
        val _ = os
          .proc("mvn", "-q", "spotless:apply")
          .call(cwd = os.pwd, check = false)

      reviewAndFixLoop(
        coder = coder,
        sessionId = sid,
        reviewers = reviewers,
        reviewerSelection = ReviewerSelector.llmDriven(claude.haiku),
        task = task.title.value
      )

  // Documentation pass uses the same session — it needs the cross-task
  // context to update docs coherently. Skipped on an empty plan.
  sessionId.foreach: sid =>
    stage("Update documentation"):
      val _ = coder.autonomous.continueSession(
        sid,
        "All tasks done. Update project docs (README, doc-comments) based " +
          "on the changes made. Only update what's affected — no new sections."
      )
      git.commit("docs: update for completed work").orThrow
