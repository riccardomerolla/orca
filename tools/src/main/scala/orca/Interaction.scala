package orca

/** The channel that connects a flow to its user. `listeners` are registered on
  * the event dispatcher so the channel can show stage progress, streamed
  * output, token totals, and errors. `drive` runs a live interactive session:
  * it consumes [[ConversationEvent]]s in order, renders them, and routes
  * user input + tool-approval decisions back via the [[Conversation]] handle.
  * `TerminalInteraction` is the default; a Slack or HTTP implementation can be
  * substituted by passing `interaction = ...` to `flow(...)`.
  *
  * The legacy `runInteractive(handle)` path is preserved until the claude
  * backend is fully cut over to stream-json; new interactive backends should
  * implement `drive` and leave `runInteractive` throwing.
  */
trait Interaction:
  def listeners: List[OrcaListener]

  /** Drive a live interactive session to completion. Returns the final
    * [[LlmResult]] on success, throws [[OrcaInteractiveCancelled]] if the
    * user cancelled mid-session, or any [[OrcaFlowException]] subtype for
    * other failures.
    */
  def drive[B <: Backend](conversation: Conversation[B]): LlmResult[B]

  /** Legacy path: hand the terminal to a child process that owns stdio
    * itself. Will be retired once every backend moves to the driver-based
    * [[drive]] shape. New implementations may leave this as
    * `throw new UnsupportedOperationException`.
    */
  def runInteractive[B <: Backend](handle: InteractiveHandle[B]): LlmResult[B]
