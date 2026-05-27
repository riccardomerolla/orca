package orca.backend

import orca.OrcaInteractiveCancelled
import orca.events.{OrcaEvent, OrcaListener}
import orca.llm.BackendTag

/** Drains a [[Conversation]] for the autonomous path: walks every
  * [[ConversationEvent]] off the iterator, emits a matching [[OrcaEvent]] to
  * the listener for the user-visible ones, then returns the awaited
  * `LlmResult`.
  *
  * Buffered text flushes at every `AssistantTurnEnd` (as `OrcaEvent.AssistantMessage`).
  * Structured mode (`conv.outputSchema.isDefined`) withholds the most recent
  * turn — if another turn follows it was intermediate prose (flush); if the
  * stream ends with it withheld it was the JSON payload (drop, the caller
  * surfaces it via `OrcaEvent.StructuredResult`). End-of-stream flushes any
  * unfinished buffer outside structured mode so a mid-turn crash doesn't lose
  * partial output.
  *
  * Other mappings: `AssistantToolCall(name, raw)` → `OrcaEvent.ToolUse(name,
  * raw)` (raw JSON passes through; the terminal listener summarises);
  * `AssistantThinkingDelta` dropped; `ConversationEvent.Error` re-emits;
  * `ToolResult`/`UserMessage`/`ApproveTool`/`UserQuestion` swallowed.
  *
  * `awaitResult()`'s `Left(OrcaInteractiveCancelled)` becomes a thrown
  * `OrcaInteractiveCancelled` so autonomous callers — which never expose a
  * cancel button — don't have to special-case a value they could never have
  * produced.
  */
private[orca] object Conversations:

  def drainAutonomous[B <: BackendTag](
      conv: Conversation[B],
      events: OrcaListener = OrcaListener.noop
  ): LlmResult[B] =
    val structuredMode = conv.outputSchema.isDefined
    val textBuf = new StringBuilder
    // Previously-closed turn's text, kept around in structured mode while we
    // wait to see if it's followed by another turn.
    var withheld: Option[String] = None
    def closeTurn(): Unit =
      if textBuf.nonEmpty then
        val text = textBuf.toString
        textBuf.clear()
        if structuredMode then
          withheld.foreach(p => events.onEvent(OrcaEvent.AssistantMessage(p)))
          withheld = Some(text)
        else events.onEvent(OrcaEvent.AssistantMessage(text))
    try
      conv.events.foreach:
        case ConversationEvent.AssistantToolCall(name, raw) =>
          events.onEvent(OrcaEvent.ToolUse(name, raw))
        case ConversationEvent.AssistantTextDelta(delta) =>
          val _ = textBuf.append(delta)
        case ConversationEvent.AssistantThinkingDelta(_) => ()
        case ConversationEvent.AssistantTurnEnd          => closeTurn()
        case ConversationEvent.Error(msg) =>
          events.onEvent(OrcaEvent.Error(msg))
        // Approval / ask_user shouldn't reach an autonomous drain (no MCP,
        // all tools pre-approved) — drop rather than crash if they do.
        case _ => ()
    finally
      // try-body may have thrown mid-turn; flush what we can. Class scaladoc
      // explains the structured-mode drop.
      closeTurn()
      if !structuredMode then
        withheld.foreach(p => events.onEvent(OrcaEvent.AssistantMessage(p)))
    conv.awaitResult() match
      case Right(result) => result
      // Autonomous callers can't produce a Left; surface as a throw so the
      // LlmResult call shape is honoured.
      case Left(cancelled) => throw cancelled
