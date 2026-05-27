package orca.tools.claude

import orca.llm.{AutoApprove, BackendTag, LlmConfig, Model, SessionId}
import orca.events.{Usage}
import orca.{OrcaFlowException}
import orca.backend.{ApprovalDecision, ConversationEvent, LlmResult}
import orca.backend.StreamConversation
import orca.subprocess.PipedCliProcess
import orca.tools.claude.streamjson.{
  ContentBlock,
  ControlDecision,
  ControlRequestBody,
  InboundMessage,
  OutboundMessage,
  StreamEventPayload
}


/** Drives a stream-json conversation with claude to completion.
  *
  * Boilerplate (reader thread, event queue, outcome lifecycle, stderr drain)
  * lives in [[StreamConversation]]; this class supplies the claude-specific
  * protocol translation: NDJSON → [[InboundMessage]] → `ConversationEvent`s,
  * plus auto-approve policy for tools listed in `config.autoApprove`. Outbound
  * writes (user turns, tool-approval responses) happen on the channel's thread
  * via `writeOutbound`.
  */
private[claude] class ClaudeConversation(
    process: PipedCliProcess,
    config: LlmConfig,
    initialPrompt: String = "",
    val outputSchema: Option[String] = None,
    /** The ask_user resource bundle for this conversation, or `None` for
      * autonomous calls / tests. Drives `canAskUser`, spawns the bridge
      * drainer thread, and closes on `onFinalize` (closing the bridge first
      * so any in-flight `ask` errors before the Netty binding tears down,
      * then the server, then any `extras` — the workDir-local
      * `.orca-mcp-<port>.json` config file).
      */
    askUser: Option[orca.backend.mcp.AskUserResources] = None
) extends StreamConversation[BackendTag.ClaudeCode.type](
      process = process,
      backendName = "claude",
      initialPrompt = initialPrompt
    ):

  import StreamConversation.Outcome

  // The reader thread is the sole writer for all three fields below.
  // No cross-thread visibility concerns: reads happen on the same thread
  // immediately after writes, within `handle(...)` dispatch. Plain `var`s
  // suffice — atomics would be theatre.

  /** Captured from the `system.init` message so `handleResult` can fall back to
    * it when the `result` message itself doesn't carry the resolved model id.
    * Some Claude CLI versions emit it in one but not both.
    */
  private var initModel: Option[String] = None

  /** Set when a text or thinking delta streams during the current turn,
    * cleared when the full turn message lands. Gates the fallback in
    * `handleAssistantTurn` that re-emits Text/Thinking blocks when no
    * partials arrived (older claude builds, partials disabled). Tool-use
    * dedup is tracked separately via [[streamedToolUseIds]].
    */
  private var deltasSinceTurnBoundary: Boolean = false

  /** Tool-use ids of `ask_user` calls suppressed in `handleAssistantTurn`.
    * `handleUserTurn` drops the matching `tool_result` so the user's typed
    * answer doesn't re-render as `⎿ <answer>` right after the UserQuestion
    * prompt already surfaced it.
    */
  private var suppressedToolUseIds: Set[String] = Set.empty

  /** Tool-use blocks currently being assembled from streaming events. Keyed
    * by the per-message `content_block` index; drained on `content_block_stop`
    * where the assembled `AssistantToolCall` is emitted.
    */
  private var inProgressToolUses: Map[Int, ClaudeConversation.ToolUseAccum] =
    Map.empty

  /** Tool-use ids already emitted via the streaming path. `handleAssistantTurn`
    * skips these so the same call doesn't render twice (once mid-stream, once
    * from the final turn message).
    */
  private var streamedToolUseIds: Set[String] = Set.empty

  // --- Conversation surface (only the bit not covered by the base) ---

  def sendUserMessage(text: String): Unit =
    writeOutbound(OutboundMessage.UserText(text))

  // True when the backend spun up an AskUserMcpServer for this session.
  // Mid-session user input doesn't flow through `sendUserMessage` (stdin
  // is closed right after the initial prompt); it flows through the MCP
  // tool result instead.
  def canAskUser: Boolean = askUser.isDefined

  // Drainer for the MCP bridge: each `ask_user` tool invocation surfaces
  // as a `ConversationEvent.UserQuestion`; the renderer's `respond`
  // closure delivers the typed answer back to the blocked handler.
  askUser.foreach(r => startAskUserDrainer(r.bridge))

  // Subclass fields above are assigned now; safe to spin up the reader +
  // stderr workers. See [[StreamConversation.start]].
  start()

  // --- Reader hook ---

  override protected def handleLine(line: String): Unit =
    handle(InboundMessage.parse(line))

  override protected def cleanExitWithoutResult(): Throwable =
    new OrcaFlowException(
      "claude exited cleanly but never sent a result message"
    )

  /** Release the ask_user resource bundle once the read loop has drained.
    * `AskUserResources.close` handles ordering (bridge → server → extras)
    * and swallows close-time failures so a winding-down conversation
    * doesn't mask the upstream cause.
    */
  override protected def onFinalize(): Unit =
    askUser.foreach(_.close())

  // --- Per-message dispatch ---

  private def handle(msg: InboundMessage): Unit = msg match
    case InboundMessage.SystemInit(_, model) =>
      initModel = model
    case InboundMessage.AssistantTurn(content) => handleAssistantTurn(content)
    case InboundMessage.UserTurn(content)      => handleUserTurn(content)
    case InboundMessage.Result(
          _,
          sid,
          output,
          structured,
          usage,
          isError,
          model
        ) =>
      if isError then handleResultError(output)
      else handleResult(sid, output, structured, usage, model)
    case InboundMessage.ControlRequest(reqId, body) =>
      handleControlRequest(reqId, body)
    case InboundMessage.StreamEvent(payload) =>
      translateStreamEvent(payload).foreach { evt =>
        evt match
          case _: ConversationEvent.AssistantTextDelta |
              _: ConversationEvent.AssistantThinkingDelta =>
            deltasSinceTurnBoundary = true
          case _ => ()
        eventQueue.enqueue(evt)
      }
    case InboundMessage.Unknown(_) =>
      // Unknown top-level message types are protocol drift (new
      // message kinds in newer CLI versions, etc.) — nothing the user
      // can act on, so drop silently rather than rendering ✖.
      ()

  /** Full assistant turn arrives after partials have streamed. Tool-use blocks
    * normally reach the channel via the streaming path (see
    * [[translateStreamEvent]]) — we skip them here unless that path didn't
    * emit them (older claude builds, partials disabled). Text and thinking
    * are normally already streamed as deltas; if no deltas preceded this turn
    * we fall back to emitting the whole block as a single delta.
    */
  private def handleAssistantTurn(content: List[ContentBlock]): Unit =
    val sawDeltasThisTurn = deltasSinceTurnBoundary
    deltasSinceTurnBoundary = false
    content.foreach:
      // Suppress the agent's own ToolCall block for `ask_user` — the
      // host-side bridge emits a UserQuestion event for the same exchange
      // and rendering the tool-call line on top of it is just noise.
      // Remember the id so `handleUserTurn` can also drop the matching
      // tool_result (otherwise the user's typed answer re-renders as
      // `⎿ <answer>` after the prompt already surfaced it).
      case ContentBlock.ToolUse(id, name, _)
          if name == ClaudeBackend.AskUserToolName =>
        suppressedToolUseIds = suppressedToolUseIds + id
      case ContentBlock.ToolUse(id, _, _)
          if streamedToolUseIds.contains(id) =>
        // Already emitted from `content_block_stop`; drop the duplicate.
        streamedToolUseIds = streamedToolUseIds - id
      case ContentBlock.ToolUse(_, name, rawInput) =>
        eventQueue.enqueue(ConversationEvent.AssistantToolCall(name, rawInput))
      case ContentBlock.Text(text) if !sawDeltasThisTurn =>
        eventQueue.enqueue(ConversationEvent.AssistantTextDelta(text))
      case ContentBlock.Thinking(text) if !sawDeltasThisTurn =>
        eventQueue.enqueue(ConversationEvent.AssistantThinkingDelta(text))
      case _ => ()
    eventQueue.enqueue(ConversationEvent.AssistantTurnEnd)

  /** User turns arriving from the subprocess echo our own input, except they
    * also carry `tool_result` blocks the SDK injected after running a tool —
    * surface those so the channel can render the outcome.
    */
  private def handleUserTurn(content: List[ContentBlock]): Unit =
    content.foreach:
      case ContentBlock.ToolResult(toolUseId, _, _)
          if suppressedToolUseIds.contains(toolUseId) =>
        // Paired with a suppressed `ask_user` ToolUse; the user has already
        // seen their own typed answer at the prompt, so don't echo it back.
        suppressedToolUseIds = suppressedToolUseIds - toolUseId
      case ContentBlock.ToolResult(_, body, isError) =>
        eventQueue.enqueue(
          ConversationEvent.ToolResult(
            toolName = "",
            ok = !isError,
            content = body
          )
        )
      case _ => ()

  private def handleResult(
      sid: String,
      output: Option[String],
      structured: Option[String],
      usage: Usage,
      model: Option[String]
  ): Unit =
    val result = LlmResult(
      sessionId = SessionId[BackendTag.ClaudeCode.type](sid),
      output = structured.orElse(output).getOrElse(""),
      usage = usage,
      // Fall back to the model claude announced in system.init when the
      // result message omits it.
      model = model.orElse(initModel).map(Model.apply)
    )
    val _ = outcomeRef.compareAndSet(None, Some(Outcome.Success(result)))

  /** Claude sets `is_error: true` for out-of-band failures (API errors, rate
    * limits, auth problems) that happen at the CLI boundary rather than inside
    * a turn. Treat these as session-ending failures rather than feeding the
    * error body into the downstream response parser, which might otherwise
    * accept a `{"type":"error",...}` payload as a structurally valid agent
    * output.
    *
    * If text deltas have already streamed in this turn, the user has already
    * seen the body — emit a short marker rather than repeating the full text.
    * The `Outcome.Failed` always carries the full message for `awaitResult` to
    * surface.
    */
  private def handleResultError(output: Option[String]): Unit =
    val message =
      output.filter(_.nonEmpty).getOrElse("claude reported is_error")
    val displayed =
      if deltasSinceTurnBoundary then "session failed (see message above)"
      else message
    eventQueue.enqueue(ConversationEvent.Error(displayed))
    val _ = outcomeRef.compareAndSet(
      None,
      Some(
        Outcome.Failed(
          new OrcaFlowException(s"claude session failed: $message")
        )
      )
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
    case AutoApprove.All         => true
    case AutoApprove.Only(tools) => tools.contains(toolName)

  /** Translate one stream-event payload into a `ConversationEvent`, or `None`
    * if the payload only updates internal state (a tool-use block being
    * assembled across `content_block_start` + `input_json_delta` events). The
    * eventual `AssistantToolCall` is returned at `content_block_stop` so the
    * UI sees per-tool-call progress instead of a turn-end batch.
    *
    * Text and thinking deltas pass straight through — claude has already
    * chunked them and the renderer handles re-assembly.
    */
  private def translateStreamEvent(
      payload: StreamEventPayload
  ): Option[ConversationEvent] = payload match
    case StreamEventPayload.TextDelta(_, text) =>
      Some(ConversationEvent.AssistantTextDelta(text))
    case StreamEventPayload.ThinkingDelta(_, text) =>
      Some(ConversationEvent.AssistantThinkingDelta(text))
    case StreamEventPayload.ContentBlockStart(idx, block) =>
      block match
        case ContentBlock.ToolUse(id, name, _)
            if name == ClaudeBackend.AskUserToolName =>
          // ask_user is surfaced through the MCP bridge, not as a ToolCall.
          // The matching full-turn block is also suppressed; record the id
          // so `handleUserTurn` can drop the paired `tool_result`.
          suppressedToolUseIds = suppressedToolUseIds + id
          None
        case ContentBlock.ToolUse(id, name, _) =>
          // Args arrive piecewise via input_json_delta; seed an empty buffer
          // and emit the AssistantToolCall when the matching
          // content_block_stop closes the block.
          inProgressToolUses =
            inProgressToolUses +
              (idx -> ClaudeConversation.ToolUseAccum(id, name))
          None
        case _ => None
    case StreamEventPayload.InputJsonDelta(idx, partial) =>
      inProgressToolUses =
        inProgressToolUses.updatedWith(idx)(_.map(_.append(partial)))
      None
    case StreamEventPayload.ContentBlockStop(idx) =>
      inProgressToolUses.get(idx).map: acc =>
        inProgressToolUses = inProgressToolUses - idx
        streamedToolUseIds = streamedToolUseIds + acc.id
        ConversationEvent.AssistantToolCall(acc.name, acc.input)
    case _ =>
      None // unhandled stream-event types pass through silently

  private def respond(requestId: String, decision: ApprovalDecision): Unit =
    val controlDecision = decision match
      case ApprovalDecision.Allow(update) => ControlDecision.Allow(update)
      case ApprovalDecision.Deny(reason)  => ControlDecision.Deny(reason)
    writeOutbound(OutboundMessage.ControlResponse(requestId, controlDecision))

  private def writeOutbound(msg: OutboundMessage): Unit =
    process.writeLine(OutboundMessage.toJson(msg))

private[claude] object ClaudeConversation:

  /** Accumulator for a tool-use block being assembled from `input_json_delta`
    * chunks. Holds each chunk separately and joins them only once at
    * `content_block_stop` — concat-on-append would be O(N²) over a long
    * stream of small chunks.
    */
  case class ToolUseAccum(
      id: String,
      name: String,
      chunks: Vector[String] = Vector.empty
  ):
    def append(chunk: String): ToolUseAccum = copy(chunks = chunks :+ chunk)
    def input: String = chunks.mkString
