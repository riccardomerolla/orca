package orca

/** Event the driver emits for the channel to render. One full session is
  * represented by a sequence of these, terminated by the `events` iterator
  * on [[Conversation]] closing; the final outcome (success or cancel) is
  * read via [[Conversation.result]].
  *
  * Partial deltas (`AssistantTextDelta`, `AssistantThinkingDelta`) arrive
  * as the agent streams its response. `AssistantTurnEnd` marks the
  * boundary between turns. `AssistantToolCall` is purely informational —
  * the agent narrated a tool invocation; the response mechanism (if any)
  * arrives separately as an `ApproveTool` event.
  */
enum ConversationEvent:
  case AssistantTextDelta(text: String)
  case AssistantThinkingDelta(text: String)
  case AssistantToolCall(toolName: String, rawInput: String)
  case AssistantTurnEnd

  /** The agent wants to invoke a tool and is asking our permission.
    * The channel must eventually call
    * [[Conversation.respondToTool]] with the matching `requestId`, or
    * the agent will stall.
    */
  case ApproveTool(requestId: String, toolName: String, rawInput: String)
