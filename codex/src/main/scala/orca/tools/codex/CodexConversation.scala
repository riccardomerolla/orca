package orca.tools.codex

import orca.llm.{BackendTag, Model, SessionId}
import orca.events.{Usage}
import orca.{OrcaFlowException}
import orca.backend.{ConversationEvent, LlmResult}
import orca.backend.StreamConversation
import orca.backend.mcp.{AskUserBridge, AskUserMcpServer}
import orca.subprocess.PipedCliProcess
import orca.tools.codex.jsonl.{FileChangeDetail, InboundEvent, Item}

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec

import java.util.concurrent.atomic.AtomicReference
import scala.util.control.NonFatal

/** Drives a `codex exec --json` session to completion.
  *
  * Boilerplate (reader thread, event queue, outcome lifecycle, stderr drain)
  * lives in [[StreamConversation]]; this class supplies the codex-specific
  * protocol translation: JSONL → [[InboundEvent]] → `ConversationEvent`s.
  *
  * Notable parity gaps vs. claude (deliberate, driven by codex's JSONL protocol
  * — see [[../../../adr/0007-codex-exec-jsonl-driver.md ADR 0007]]):
  *   - codex emits whole `agent_message` items, not per-token deltas. Each
  *     agent message becomes one [[ConversationEvent.AssistantTextDelta]]
  *     followed by [[ConversationEvent.AssistantTurnEnd]].
  *   - codex doesn't negotiate tool approvals over the JSONL stream;
  *     `LlmConfig.autoApprove` is pre-baked into the spawn args by
  *     [[CodexArgs]]. [[ConversationEvent.ApproveTool]] is never emitted from
  *     this driver.
  *   - `codex exec` reads one prompt and exits; multi-turn happens by spawning
  *     a fresh `codex exec resume`. So `sendUserMessage` is a no-op (write to a
  *     closed pipe) — the channel reaches the next turn via
  *     [[orca.LlmBackend.continueInteractive]].
  *   - **`LlmResult.output` is synthesised, not delivered.** ADR 0006's "the
  *     final result message carries the structured output" description fits
  *     claude (an explicit `result` line on stdout with a `result` field).
  *     codex has no equivalent terminal message — it just emits
  *     `turn.completed` with usage. We treat the *last* `item.completed` of
  *     type `agent_message` before `turn.completed` as the structured payload,
  *     snapshotting its text into [[lastAgentMessage]] on every agent message
  *     and building the `LlmResult` from that snapshot when the turn closes.
  *     The `DefaultPromptTemplate` already instructs the agent to make its
  *     final message JSON-only, so the snapshot contract holds in practice.
  */
private[codex] class CodexConversation(
    process: PipedCliProcess,
    initialPrompt: String = "",
    val outputSchema: Option[String] = None,
    askUserBridge: Option[AskUserBridge] = None,
    /** Resources to close when this conversation ends — typically the MCP
      * server bound for this conversation's `ask_user` tool. Closed from
      * `onFinalize` after the reader loop exits.
      */
    sessionResources: List[AutoCloseable] = Nil
) extends StreamConversation[BackendTag.Codex.type](
      process = process,
      backendName = "codex",
      initialPrompt = initialPrompt
    ):

  import CodexConversation.*
  import StreamConversation.Outcome

  private val sessionIdRef = new AtomicReference[String]("")
  private val modelRef = new AtomicReference[Option[String]](None)

  /** The most recent agent_message text the model produced. See the class
    * scaladoc for why we synthesise rather than receive.
    */
  private val lastAgentMessage = new AtomicReference[String]("")

  /** Bounded ring of trimmed stderr lines (post-filter), kept so a non-zero
    * exit or clean-exit-without-result can carry the actual failure context
    * back to the caller in the thrown exception — listener subscribers already
    * saw each line as a `ConversationEvent.Error`, but a noop listener (tests,
    * simple scripts) would otherwise lose the diagnostic entirely. Capped on
    * both line count and byte total so a runaway agent can't OOM us.
    */
  private val stderrBuffer = new AtomicReference[Vector[String]](Vector.empty)

  /** MCP item ids whose `AssistantToolCall` echo we drop — the host-side
    * bridge has already surfaced the corresponding `UserQuestion` event, so
    * rendering the tool call on top would be noise. The matching
    * `item.completed` is also suppressed: the user's typed answer would
    * otherwise re-render as `⎿ <answer>` after the prompt surfaced it.
    *
    * Single-threaded reader (the JSONL reader thread is the sole writer),
    * so a plain `var` Set is enough.
    */
  private var suppressedMcpItemIds: Set[String] = Set.empty

  // Subclass fields above are assigned now; safe to spin up the reader +
  // stderr workers. See [[StreamConversation.start]].
  askUserBridge.foreach: bridge =>
    val t = new Thread(
      () => askUserDrainLoop(bridge),
      "codex-conversation-ask-user"
    )
    t.setDaemon(true)
    t.start()

  start()

  private def askUserDrainLoop(bridge: AskUserBridge): Unit =
    try
      while !Thread.currentThread().isInterrupted do
        val q = bridge.nextQuestion()
        eventQueue.enqueue(
          ConversationEvent.UserQuestion(q.question, q.respond)
        )
    catch
      // Bridge channel closure (onFinalize → bridge.close()) propagates as
      // a ChannelClosedException from take(). Exit quietly. NonFatal so an
      // InterruptedException still propagates if something else interrupts
      // this thread.
      case NonFatal(_) => ()

  // --- Conversation surface ---

  /** Codex exec consumes its prompt argv-side and ignores stdin thereafter;
    * injecting more user turns mid-session isn't supported. The contract still
    * requires a callable method — this is a no-op.
    */
  def sendUserMessage(text: String): Unit = ()

  // Codex exec has no in-session user-message channel over stdin (ADR
  // 0007), but the agent CAN reach the user via the `ask_user` MCP tool
  // when a bridge is wired. The flag tracks the bridge's presence rather
  // than the stdin channel.
  def canAskUser: Boolean = askUserBridge.isDefined

  // --- Reader hooks ---

  override protected def handleLine(line: String): Unit =
    handle(InboundEvent.parse(line))

  /** codex prints known-benign noise on every exec invocation:
    *
    *   - `Reading additional input from stdin…` whenever stdin is piped
    *     (we always pipe stdin, even though we immediately close it).
    *   - `ERROR codex_core::session: failed to record rollout items: thread
    *     <id> not found` during shutdown — fires inside codex's cleanup after
    *     the rollout writer is already torn down. The rollout file is still
    *     written correctly to `~/.codex/sessions/`; the message is harmless.
    *
    * Filter both, plus empty lines. Anything else passes through with the
    * default backend-prefixed Error event AND appends to the bounded
    * [[stderrBuffer]] so the failure exception can include them.
    */
  override protected def handleStderr(line: String): Unit =
    val trimmed = line.trim
    if trimmed.nonEmpty && !CodexConversation.isKnownStderrNoise(trimmed) then
      eventQueue.enqueue(ConversationEvent.Error(s"codex: $trimmed"))
      val _ = stderrBuffer.updateAndGet(appendBounded(_, trimmed))

  /** Bounded wait for the stderr drain so any trailing error lines (which the
    * consumer can't see once we close the queue) reach the events queue.
    * Then release session-scoped resources: close the ask_user bridge first
    * (errors any in-flight `ask`), then the MCP server's Netty binding.
    * Both wrapped in NonFatal — close-time failure mustn't mask whatever
    * already failed upstream.
    */
  override protected def onFinalize(): Unit =
    try stderrDrainThread.join(StderrDrainTimeoutMs)
    catch
      // Interrupt while joining means the parent is shutting down.
      // Restore the flag so callers up-stack can react.
      case _: InterruptedException => Thread.currentThread().interrupt()
    askUserBridge.foreach: bridge =>
      try bridge.close()
      catch case NonFatal(_) => ()
    sessionResources.foreach: r =>
      try r.close()
      catch case NonFatal(_) => ()

  override protected def cleanExitWithoutResult(): Throwable =
    // Defer the framing to the base so the diagnosticContext below gets
    // folded in with the same shape as the non-zero-exit branch.
    new OrcaFlowException(
      appendContext(
        "codex exited cleanly but never sent a turn.completed event"
      )
    )

  /** Recent stderr lines, formatted as a `stderr:` block. The base layer
    * ([[StreamConversation.appendContext]]) owns the leading-newline + indent
    * framing so this override returns just the payload.
    */
  override protected def diagnosticContext: Option[String] =
    val lines = stderrBuffer.get()
    if lines.isEmpty then None
    else Some(lines.mkString("stderr:\n    ", "\n    ", ""))

  // --- Per-event dispatch ---

  private def handle(event: InboundEvent): Unit = event match
    case InboundEvent.ThreadStarted(threadId, model) =>
      sessionIdRef.set(threadId)
      modelRef.set(model)
    case InboundEvent.TurnStarted          => ()
    case InboundEvent.TurnCompleted(usage) => handleTurnCompleted(usage)
    case InboundEvent.ItemStarted(item)    => handleItemStarted(item)
    case InboundEvent.ItemCompleted(item)  => handleItemCompleted(item)
    case InboundEvent.Unknown(_)           =>
      // Forward-compat: codex may add new top-level event types; drop
      // them silently rather than rendering ✖.
      ()

  private def handleItemStarted(item: Item): Unit = item match
    case Item.CommandExecution(_, command, _, _, _) =>
      eventQueue.enqueue(
        ConversationEvent.AssistantToolCall(
          toolName = "bash",
          rawInput = writeToString(BashInput(command))
        )
      )
    case Item.FileChange(_, changes, _) =>
      eventQueue.enqueue(
        ConversationEvent.AssistantToolCall(
          toolName = "file_change",
          rawInput = writeToString(FileChangeInput(changes.map(toWire)))
        )
      )
    case Item.McpToolCall(id, server, tool, _, _, _)
        if server == CodexArgs.AskUserMcpName &&
          tool == AskUserMcpServer.ToolSlug =>
      // ask_user is surfaced through the host-side bridge as a
      // UserQuestion event; the matching item.completed echo is dropped
      // too — the user has already seen their typed answer at the prompt.
      suppressedMcpItemIds = suppressedMcpItemIds + id
    case Item.McpToolCall(_, server, tool, args, _, _) =>
      eventQueue.enqueue(
        ConversationEvent.AssistantToolCall(
          toolName = mcpToolName(server, tool),
          rawInput = args
        )
      )
    case _ =>
      // agent_message / reasoning announce themselves at completion;
      // Other items pass through without a started event. Nothing to do.
      ()

  private def handleItemCompleted(item: Item): Unit = item match
    case Item.AgentMessage(_, text) =>
      lastAgentMessage.set(text)
      eventQueue.enqueue(ConversationEvent.AssistantTextDelta(text))
      eventQueue.enqueue(ConversationEvent.AssistantTurnEnd)
    case Item.Reasoning(_, text) if text.nonEmpty =>
      eventQueue.enqueue(ConversationEvent.AssistantThinkingDelta(text))
    case Item.Reasoning(_, _) => ()
    case Item.CommandExecution(_, _, output, exitCode, status) =>
      eventQueue.enqueue(
        ConversationEvent.ToolResult(
          toolName = "bash",
          ok = exitCode.contains(0) && status == "completed",
          content = output
        )
      )
    case Item.FileChange(_, changes, status) =>
      eventQueue.enqueue(
        ConversationEvent.ToolResult(
          toolName = "file_change",
          ok = status == "completed",
          content = changes.map(c => s"${c.kind} ${c.path}").mkString("\n")
        )
      )
    case Item.McpToolCall(id, _, _, _, _, _)
        if suppressedMcpItemIds.contains(id) =>
      // Matched a suppressed ask_user call started above; drop the
      // mirrored completion and clear the id.
      suppressedMcpItemIds = suppressedMcpItemIds - id
    case Item.McpToolCall(_, server, tool, _, result, status) =>
      eventQueue.enqueue(
        ConversationEvent.ToolResult(
          toolName = mcpToolName(server, tool),
          ok = status == "completed",
          content = result.getOrElse("")
        )
      )
    case Item.Other(_, _) =>
      ()

  /** Compose the user-facing tool name from codex's `(server, tool)` pair.
    * Codex namespaces MCP tools by server, so a dotted form reads naturally
    * in the renderer log and stays distinct from the bare `bash` /
    * `file_change` names used by codex's built-in items.
    */
  private def mcpToolName(server: String, tool: String): String =
    s"$server.$tool"

  private def handleTurnCompleted(usage: Usage): Unit =
    val result = LlmResult(
      sessionId = SessionId[BackendTag.Codex.type](sessionIdRef.get()),
      output = lastAgentMessage.get(),
      usage = usage,
      model = modelRef.get().map(Model.apply)
    )
    val _ = outcomeRef.compareAndSet(None, Some(Outcome.Success(result)))

  private def toWire(c: FileChangeDetail): FileChangeWire =
    FileChangeWire(c.path, c.kind)

private[codex] object CodexConversation:

  /** Stderr lines codex emits unconditionally that carry no diagnostic
    * value — filtered before they reach the event queue. See
    * [[CodexConversation.handleStderr]] for what each line means.
    */
  private[codex] def isKnownStderrNoise(line: String): Boolean =
    line.startsWith("Reading additional input from stdin") ||
      line.contains(
        "codex_core::session: failed to record rollout items"
      )

  /** Bounded wait for the stderr thread to finish draining before the event
    * queue is closed. Long enough that real EOF-after-process- exit lands;
    * short enough that a stalled child doesn't deadlock `awaitResult`.
    */
  private val StderrDrainTimeoutMs: Long = 500L

  /** Cap on lines kept in [[stderrBuffer]]. Sized for a typical stack trace
    * plus a brief explanation — enough to diagnose a failure inline, bounded so
    * a chatty subprocess can't grow memory. `private[codex]` so cap-edge tests
    * can size their inputs against it.
    */
  private[codex] val StderrMaxLines: Int = 20

  /** Soft byte cap on [[stderrBuffer]], counted across kept lines. Trims from
    * the front (oldest) when exceeded, same as the line cap, so the most recent
    * (typically most diagnostic) lines stay. `private[codex]` for the same
    * test-visibility reason as [[StderrMaxLines]].
    */
  private[codex] val StderrMaxBytes: Int = 4096

  /** Append `line` to `buf` while respecting both caps. Drops from the head
    * (oldest line first) until the result fits. A single line that exceeds
    * `StderrMaxBytes` on its own is kept anyway — one oversized line is more
    * useful than empty diagnostics. Lives on the companion so the
    * `AtomicReference.updateAndGet` callback stays a pure function.
    * `private[codex]` so the cap-edge tests can exercise it directly.
    */
  private[codex] def appendBounded(
      buf: Vector[String],
      line: String
  ): Vector[String] =
    var result = buf :+ line
    while result.size > StderrMaxLines do result = result.tail
    while result.size > 1 && result.map(_.length).sum > StderrMaxBytes do
      result = result.tail
    result

  /** Synthetic JSON the driver hands the renderer for `bash` tool calls —
    * codex's `command_execution` items don't natively carry a JSON-shaped
    * input, so we wrap the command string in a one-key object the renderer can
    * introspect.
    */
  private case class BashInput(command: String) derives ConfiguredJsonValueCodec

  private case class FileChangeWire(path: String, kind: String)
      derives ConfiguredJsonValueCodec

  private case class FileChangeInput(changes: List[FileChangeWire])
      derives ConfiguredJsonValueCodec
