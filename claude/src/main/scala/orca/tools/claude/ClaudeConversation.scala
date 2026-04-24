package orca.tools.claude

import orca.{
  ApprovalDecision,
  AutoApprove,
  Backend,
  Conversation,
  ConversationEvent,
  LlmConfig,
  LlmResult,
  OrcaFlowException,
  OrcaInteractiveCancelled,
  SessionId,
  Usage
}
import orca.subprocess.PipedCliProcess
import orca.tools.claude.streamjson.{
  ContentBlock,
  ControlDecision,
  ControlRequestBody,
  InboundMessage,
  OutboundMessage,
  StreamEventPayload
}

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.util.control.NonFatal

/** Drives a stream-json conversation with claude to completion. Owns the
  * subprocess, a reader thread that parses inbound NDJSON, and the event
  * queue the channel consumes. Outbound writes (user turns, tool-approval
  * responses) happen directly from the channel's thread; the reader
  * thread only produces events.
  *
  * Lifecycle:
  *   1. Construct — starts a daemon reader thread.
  *   2. Channel iterates `events` and calls `sendUserMessage` /
  *      `cancel` as needed; it answers each [[ConversationEvent.ApproveTool]]
  *      by invoking the carried `respond` closure.
  *   3. The reader sees an [[InboundMessage.Result]] (clean finish) or an
  *      abnormal EOF (cancel / crash), closes the queue, and exits.
  *   4. Channel finishes draining events and calls `awaitResult()`,
  *      which returns the final [[LlmResult]] or throws
  *      [[OrcaInteractiveCancelled]].
  *
  * The driver's role is strictly protocol translation: NDJSON in →
  * [[ConversationEvent]]s out, plus auto-approve policy for tools in
  * `config.autoApprove`. It doesn't render and doesn't decide UX — that
  * belongs to the [[Interaction]].
  */
private[claude] class ClaudeConversation(
    process: PipedCliProcess,
    config: LlmConfig,
    initialPrompt: String = ""
) extends Conversation[Backend.ClaudeCode.type]:

  import ClaudeConversation.*

  private val eventQueue = new EventQueue
  private val sessionIdRef = new AtomicReference[String]("")
  private val outcomeRef = new AtomicReference[Option[Outcome]](None)
  private val cancelled = new AtomicBoolean(false)
  /** Set whenever a delta arrives in the current turn, cleared when the
    * full turn message lands. Lets `handleAssistantTurn` tell "partials
    * already streamed" from "partials disabled upstream".
    */
  private val deltasSinceTurnBoundary = new AtomicBoolean(false)

  // Surface the opening prompt to the channel before any agent output.
  // Without this, the channel sits silent while claude warms up — giving
  // the user nothing to anchor the eventual agent response against.
  if initialPrompt.nonEmpty then
    eventQueue.enqueue(ConversationEvent.UserMessage(initialPrompt))

  private val readerThread: Thread =
    val t = new Thread(() => readLoop(), "claude-conversation-reader")
    t.setDaemon(true)
    t.start()
    t

  /** Separate fork for stderr so diagnostics from claude (auth failures,
    * flag rejections, protocol errors — all of which land on stderr
    * rather than as a `result` message) surface as
    * [[ConversationEvent.Error]]s instead of being silently eaten.
    */
  private val stderrThread: Thread =
    val t = new Thread(() => stderrLoop(), "claude-conversation-stderr")
    t.setDaemon(true)
    t.start()
    t

  // --- Conversation surface ---

  def events: Iterator[ConversationEvent] = eventQueue.iterator

  def awaitResult(): LlmResult[Backend.ClaudeCode.type] =
    readerThread.join()
    outcomeRef.get() match
      case Some(Outcome.Success(r)) => r
      case Some(Outcome.Cancelled)  => throw new OrcaInteractiveCancelled()
      case Some(Outcome.Failed(e))  => throw e
      case None =>
        throw new OrcaFlowException(
          "claude interactive session ended without producing a result"
        )

  def sendUserMessage(text: String): Unit =
    writeOutbound(OutboundMessage.UserText(text))

  def cancel(): Unit =
    if cancelled.compareAndSet(false, true) then
      process.sendSigInt()
      // The reader loop will see EOF on stdoutLines and shut down the
      // queue on its own; no need to touch it from here.

  // --- Reader thread ---

  /** Debug switch: set `ORCA_DEBUG_STREAM=1` to dump every inbound
    * stdout / stderr line from claude verbatim to the parent's stderr,
    * before parsing. Lets you confirm whether the subprocess is emitting
    * anything at all.
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
        if line.trim.nonEmpty then
          eventQueue.enqueue(ConversationEvent.Error(s"claude: $line"))
    catch case NonFatal(_) => () // stderr draining is best-effort

  private def handleLine(line: String): Unit =
    try handle(InboundMessage.parse(line))
    catch
      case e: Exception =>
        eventQueue.enqueue(
          ConversationEvent.Error(
            s"Failed to parse claude stream-json line: ${e.getMessage}"
          )
        )

  private def handle(msg: InboundMessage): Unit = msg match
    case InboundMessage.SystemInit(sid)          => sessionIdRef.set(sid)
    case InboundMessage.AssistantTurn(content)   => handleAssistantTurn(content)
    case InboundMessage.UserTurn(content)        => handleUserTurn(content)
    case InboundMessage.Result(_, sid, output, structured, usage, isError) =>
      if isError then handleResultError(output)
      else handleResult(sid, output, structured, usage)
    case InboundMessage.ControlRequest(reqId, body) =>
      handleControlRequest(reqId, body)
    case InboundMessage.StreamEvent(payload) =>
      translateStreamEvent(payload).foreach { evt =>
        deltasSinceTurnBoundary.set(true)
        eventQueue.enqueue(evt)
      }
    case InboundMessage.Unknown(_) =>
      // Unknown top-level message types are protocol drift (new
      // message kinds in newer CLI versions, etc.) — nothing the user
      // can act on, so drop silently rather than rendering ✖.
      ()

  /** Full assistant turn arrives after partials have streamed. Tool-use
    * blocks only reach us here (deltas don't reconstruct them), so we
    * emit those. Text and thinking are normally already streamed as
    * deltas, but if partials were disabled upstream we'd silently drop
    * them — fall back to emitting the whole block as a single delta
    * when no deltas preceded this turn.
    */
  private def handleAssistantTurn(content: List[ContentBlock]): Unit =
    val sawDeltasThisTurn = deltasSinceTurnBoundary.getAndSet(false)
    content.foreach:
      case ContentBlock.ToolUse(_, name, rawInput) =>
        eventQueue.enqueue(ConversationEvent.AssistantToolCall(name, rawInput))
      case ContentBlock.Text(text) if !sawDeltasThisTurn =>
        eventQueue.enqueue(ConversationEvent.AssistantTextDelta(text))
      case ContentBlock.Thinking(text) if !sawDeltasThisTurn =>
        eventQueue.enqueue(ConversationEvent.AssistantThinkingDelta(text))
      case _ => ()
    eventQueue.enqueue(ConversationEvent.AssistantTurnEnd)

  /** User turns arriving from the subprocess echo our own input, except
    * they also carry `tool_result` blocks the SDK injected after running
    * a tool — surface those so the channel can render the outcome.
    */
  private def handleUserTurn(content: List[ContentBlock]): Unit =
    content.foreach:
      case ContentBlock.ToolResult(_, body, isError) =>
        eventQueue.enqueue(ConversationEvent.ToolResult(
          toolName = "",
          ok = !isError,
          content = body
        ))
      case _ => ()

  private def handleResult(
      sid: String,
      output: Option[String],
      structured: Option[String],
      usage: Usage
  ): Unit =
    val result = LlmResult(
      sessionId = SessionId[Backend.ClaudeCode.type](sid),
      output = structured.orElse(output).getOrElse(""),
      usage = usage
    )
    val _ = outcomeRef.compareAndSet(None, Some(Outcome.Success(result)))

  /** Claude sets `is_error: true` for out-of-band failures (API errors,
    * rate limits, auth problems) that happen at the CLI boundary rather
    * than inside a turn. Treat these as session-ending failures rather
    * than feeding the error body into the downstream response parser,
    * which might otherwise accept a `{"type":"error",...}` payload as
    * a structurally valid agent output.
    */
  private def handleResultError(output: Option[String]): Unit =
    val message = output.filter(_.nonEmpty).getOrElse("claude reported is_error")
    eventQueue.enqueue(ConversationEvent.Error(message))
    val _ = outcomeRef.compareAndSet(
      None,
      Some(Outcome.Failed(new OrcaFlowException(s"claude session failed: $message")))
    )

  private def handleControlRequest(
      requestId: String,
      body: ControlRequestBody
  ): Unit = body match
    case ControlRequestBody.CanUseTool(name, rawInput) =>
      if autoApproves(name) then respond(requestId, ApprovalDecision.Allow())
      else
        eventQueue.enqueue(
          ConversationEvent.ApproveTool(
            toolName = name,
            rawInput = rawInput,
            respond = decision => respond(requestId, decision)
          )
        )
    case ControlRequestBody.Unknown(subtype) =>
      eventQueue.enqueue(
        ConversationEvent.Error(s"Unknown control_request subtype: $subtype")
      )

  private def autoApproves(toolName: String): Boolean = config.autoApprove match
    case AutoApprove.All           => true
    case AutoApprove.Only(tools)   => tools.contains(toolName)

  private def translateStreamEvent(
      payload: StreamEventPayload
  ): Option[ConversationEvent] = payload match
    case StreamEventPayload.TextDelta(_, text) =>
      Some(ConversationEvent.AssistantTextDelta(text))
    case StreamEventPayload.ThinkingDelta(_, text) =>
      Some(ConversationEvent.AssistantThinkingDelta(text))
    case _ =>
      None // block start/stop, input-json deltas, unhandled — driver ignores

  private def respond(requestId: String, decision: ApprovalDecision): Unit =
    val controlDecision = decision match
      case ApprovalDecision.Allow(update) => ControlDecision.Allow(update)
      case ApprovalDecision.Deny(reason)  => ControlDecision.Deny(reason)
    writeOutbound(OutboundMessage.ControlResponse(requestId, controlDecision))

  private def writeOutbound(msg: OutboundMessage): Unit =
    process.writeLine(OutboundMessage.toJson(msg))

  private def finalizeLoop(): Unit =
    val finalOutcome = outcomeRef.get() match
      case Some(existing) => existing
      case None if cancelled.get() => Outcome.Cancelled
      case None => outcomeFromExit(process.tryExitCode)
    val _ = outcomeRef.compareAndSet(None, Some(finalOutcome))
    eventQueue.close()

  private def outcomeFromExit(exitCode: Option[Int]): Outcome = exitCode match
    case Some(0) =>
      Outcome.Failed(
        new OrcaFlowException(
          "claude exited cleanly but never sent a result message"
        )
      )
    case Some(code) =>
      Outcome.Failed(new OrcaFlowException(s"claude exited with code $code"))
    case None => Outcome.Cancelled

private[claude] object ClaudeConversation:

  /** Internal outcome of the session as the reader sees it. */
  private enum Outcome:
    case Success(result: LlmResult[Backend.ClaudeCode.type])
    case Cancelled
    case Failed(error: Throwable)

  /** Blocking queue + single-consumer iterator. `close()` signals
    * end-of-stream to whichever thread is iterating.
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
