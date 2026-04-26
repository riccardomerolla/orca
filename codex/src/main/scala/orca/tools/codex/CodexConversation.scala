package orca.tools.codex

import orca.{
  Backend,
  Conversation,
  ConversationEvent,
  LlmResult,
  OrcaFlowException,
  OrcaInteractiveCancelled,
  SessionId,
  Usage
}
import orca.subprocess.PipedCliProcess
import orca.tools.codex.jsonl.{FileChangeDetail, InboundEvent, Item}

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.util.control.NonFatal

/** Drives a `codex exec --json` session to completion. Owns the
  * subprocess, a daemon reader thread that parses JSONL events, and
  * the event queue the channel consumes. Mirrors [[orca.tools.claude.ClaudeConversation]]
  * structurally; the differences are downstream of the codex JSONL
  * protocol (see [[../../../adr/0007-codex-exec-jsonl-driver.md ADR
  * 0007]] for the probe findings).
  *
  * Notable parity gaps vs. claude:
  *   - codex emits whole `agent_message` items, not per-token deltas.
  *     Each agent message becomes one [[ConversationEvent.AssistantTextDelta]]
  *     followed by [[ConversationEvent.AssistantTurnEnd]].
  *   - codex doesn't negotiate tool approvals over the JSONL stream;
  *     `LlmConfig.autoApprove` is pre-baked into the spawn args by
  *     [[CodexArgs]]. [[ConversationEvent.ApproveTool]] is never
  *     emitted from this driver.
  *   - `codex exec` reads one prompt and exits; multi-turn happens by
  *     spawning a fresh `codex exec resume`. So `sendUserMessage` is a
  *     no-op (write to a closed pipe) — the channel reaches the next
  *     turn via [[orca.LlmBackend.continueInteractive]].
  */
private[codex] class CodexConversation(
    process: PipedCliProcess,
    initialPrompt: String = ""
) extends Conversation[Backend.Codex.type]:

  import CodexConversation.*

  private val eventQueue = new EventQueue
  private val sessionIdRef = new AtomicReference[String]("")
  private val outcomeRef = new AtomicReference[Option[Outcome]](None)
  private val cancelled = new AtomicBoolean(false)
  /** The most recent agent_message text the model produced. codex
    * doesn't tag a "final" message; the convention from the probes is
    * that the message immediately preceding `turn.completed` is the
    * structured payload. We keep updating this on every agent_message
    * and snapshot it when `turn.completed` arrives.
    */
  private val lastAgentMessage = new AtomicReference[String]("")

  // Surface the opening prompt so the channel has something to anchor
  // the eventual agent response against. Same shape as ClaudeConversation.
  if initialPrompt.nonEmpty then
    eventQueue.enqueue(ConversationEvent.UserMessage(initialPrompt))

  private val readerThread: Thread =
    val t = new Thread(() => readLoop(), "codex-conversation-reader")
    t.setDaemon(true)
    t.start()
    t

  /** Stderr drain. codex emits a benign "Reading additional input from
    * stdin..." line on every session; that's filtered out. Real errors
    * (auth failures, resume failures, protocol issues) surface as
    * [[ConversationEvent.Error]]s.
    */
  private val stderrThread: Thread =
    val t = new Thread(() => stderrLoop(), "codex-conversation-stderr")
    t.setDaemon(true)
    t.start()
    t

  // --- Conversation surface ---

  def events: Iterator[ConversationEvent] = eventQueue.iterator

  def awaitResult(): Either[OrcaInteractiveCancelled, LlmResult[Backend.Codex.type]] =
    readerThread.join()
    outcomeRef.get() match
      case Some(Outcome.Success(r)) => Right(r)
      case Some(Outcome.Cancelled)  => Left(new OrcaInteractiveCancelled())
      case Some(Outcome.Failed(e))  => throw e
      case None =>
        throw new OrcaFlowException(
          "codex interactive session ended without producing a result"
        )

  /** Codex exec consumes its prompt argv-side and closes stdin after
    * EOF, so injecting more user turns mid-session isn't supported.
    * The contract still requires a callable method; this is a no-op.
    */
  def sendUserMessage(text: String): Unit = ()

  def cancel(): Unit =
    if cancelled.compareAndSet(false, true) then
      process.sendSigInt()

  // --- Reader thread ---

  /** Debug switch: set `ORCA_DEBUG_STREAM=1` to dump every inbound
    * stdout / stderr line verbatim before parsing — same lever as the
    * claude driver.
    */
  private val debugStream: Boolean =
    sys.env.get("ORCA_DEBUG_STREAM").contains("1")

  private def debugLog(channel: String, line: String): Unit =
    if debugStream then
      System.err.println(s"[orca-debug $channel] $line")

  private def readLoop(): Unit =
    try
      for line <- process.stdoutLines do
        debugLog("stdout", line)
        if !cancelled.get() then handleLine(line)
    catch
      case NonFatal(e) =>
        debugLog("stdout-error", e.toString)
        val _ = outcomeRef.compareAndSet(None, Some(Outcome.Failed(e)))
    finally finalizeLoop()

  private def stderrLoop(): Unit =
    try
      for line <- process.stderrLines do
        debugLog("stderr", line)
        if isReportableStderr(line) then
          eventQueue.enqueue(ConversationEvent.Error(s"codex: ${line.trim}"))
    catch case NonFatal(_) => () // stderr draining is best-effort

  /** codex prints `Reading additional input from stdin...` on every
    * exec invocation when stdin is piped (regardless of whether we
    * actually feed it anything). It's pure noise; skip it. Empty
    * lines too.
    */
  private def isReportableStderr(line: String): Boolean =
    val trimmed = line.trim
    trimmed.nonEmpty &&
      !trimmed.startsWith("Reading additional input from stdin")

  private def handleLine(line: String): Unit =
    try handle(InboundEvent.parse(line))
    catch
      case e: Exception =>
        eventQueue.enqueue(
          ConversationEvent.Error(
            s"Failed to parse codex JSONL line: ${e.getMessage}"
          )
        )

  private def handle(event: InboundEvent): Unit = event match
    case InboundEvent.ThreadStarted(threadId) =>
      sessionIdRef.set(threadId)
    case InboundEvent.TurnStarted => ()
    case InboundEvent.TurnCompleted(usage) =>
      handleTurnCompleted(usage)
    case InboundEvent.ItemStarted(item) =>
      handleItemStarted(item)
    case InboundEvent.ItemCompleted(item) =>
      handleItemCompleted(item)
    case InboundEvent.Unknown(_) =>
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
    case Item.Other(_, _) =>
      ()

  private def handleTurnCompleted(usage: Usage): Unit =
    val result = LlmResult(
      sessionId = SessionId[Backend.Codex.type](sessionIdRef.get()),
      output = lastAgentMessage.get(),
      usage = usage
    )
    val _ = outcomeRef.compareAndSet(None, Some(Outcome.Success(result)))

  private def finalizeLoop(): Unit =
    val finalOutcome = outcomeRef.get() match
      case Some(existing) => existing
      case None if cancelled.get() => Outcome.Cancelled
      case None => outcomeFromExit(process.tryExitCode)
    val _ = outcomeRef.compareAndSet(None, Some(finalOutcome))
    // Bounded wait for the stderr drain so any trailing error lines
    // (which the consumer can't see once we close the queue) reach
    // the events queue. The cap is short — by the time stdout EOFs,
    // the process has exited and stderr should EOF nearly
    // simultaneously; if it doesn't, we'd rather lose a late
    // stderr line than hold `awaitResult` hostage on a stalled child.
    try stderrThread.join(StderrDrainTimeoutMs)
    catch case _: InterruptedException => ()
    eventQueue.close()

  private def outcomeFromExit(exitCode: Option[Int]): Outcome = exitCode match
    case Some(0) =>
      Outcome.Failed(
        new OrcaFlowException(
          "codex exited cleanly but never sent a turn.completed event"
        )
      )
    case Some(code) =>
      Outcome.Failed(
        new OrcaFlowException(s"codex exited with code $code")
      )
    case None => Outcome.Cancelled

  private def toWire(c: FileChangeDetail): FileChangeWire =
    FileChangeWire(c.path, c.kind)

private[codex] object CodexConversation:

  /** Bounded wait for the stderr thread to finish draining before the
    * event queue is closed. Long enough that real EOF-after-process-
    * exit lands; short enough that a stalled child doesn't deadlock
    * `awaitResult`.
    */
  private val StderrDrainTimeoutMs: Long = 500L

  /** Internal outcome of the session as the reader sees it. */
  private enum Outcome:
    case Success(result: LlmResult[Backend.Codex.type])
    case Cancelled
    case Failed(error: Throwable)

  /** Synthetic JSON the driver hands the renderer for `bash` tool
    * calls — codex's `command_execution` items don't natively carry
    * a JSON-shaped input, so we wrap the command string in a one-key
    * object the renderer can introspect.
    */
  private case class BashInput(command: String)
      derives ConfiguredJsonValueCodec

  private case class FileChangeWire(path: String, kind: String)
      derives ConfiguredJsonValueCodec

  private case class FileChangeInput(changes: List[FileChangeWire])
      derives ConfiguredJsonValueCodec

  /** Blocking queue + single-consumer iterator. `close()` signals
    * end-of-stream. Mirrors the claude implementation.
    */
  private final class EventQueue:
    private val queue = new LinkedBlockingQueue[Option[ConversationEvent]]()

    def enqueue(event: ConversationEvent): Unit =
      val _ = queue.offer(Some(event))

    def close(): Unit =
      val _ = queue.offer(None)

    val iterator: Iterator[ConversationEvent] = new Iterator[ConversationEvent]:
      // Single-consumer per the Conversation contract; a plain `var`
      // with a null sentinel is enough. The three states are:
      //   null      — nothing peeked yet (will block on next `hasNext`)
      //   Some(e)   — peeked event, returned by next `next()`
      //   None      — stream closed, `hasNext` stays false forever
      private var peeked: Option[ConversationEvent] = null

      def hasNext: Boolean =
        if peeked == null then peeked = queue.take()
        peeked.isDefined

      def next(): ConversationEvent =
        if !hasNext then throw new NoSuchElementException("event stream closed")
        val value = peeked.get
        peeked = null
        value
