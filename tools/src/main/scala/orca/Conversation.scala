package orca

/** One live interactive session with a backend. Owned by the driver, read
  * and written to by the channel via [[Interaction.drive]].
  *
  * `events` is a single-consumer blocking iterator that yields every
  * [[ConversationEvent]] produced by the subprocess in order. The iterator
  * terminates when the session ends — either cleanly (call
  * `result()` to retrieve the outcome) or via cancellation (calling
  * `result()` then throws [[OrcaInteractiveCancelled]]).
  *
  * `sendUserMessage`, `respondToTool`, and `cancel` are the channel's
  * levers. They may be called from any thread — implementations are
  * thread-safe — but each `ApproveTool` event must be answered exactly
  * once via `respondToTool`.
  */
trait Conversation[B <: Backend]:

  /** Events from the subprocess, in arrival order. Blocks on `next()`
    * until a line has been parsed or the session ends. `hasNext` returns
    * false once the terminal event has been consumed.
    */
  def events: Iterator[ConversationEvent]

  /** Block until the session finishes, then return its final outcome.
    * Throws [[OrcaInteractiveCancelled]] if the session was cancelled
    * (either via `cancel()` or because the subprocess died abnormally).
    * Other failures propagate as [[OrcaFlowException]].
    */
  def result(): LlmResult[B]

  /** Inject a user turn mid-conversation. */
  def sendUserMessage(text: String): Unit

  /** Respond to an outstanding tool-approval prompt. */
  def respondToTool(requestId: String, decision: ApprovalDecision): Unit

  /** Cancel the current session. The driver tears down the subprocess,
    * closes the events iterator, and `result()` throws
    * [[OrcaInteractiveCancelled]]. Calling `cancel` twice is a no-op.
    */
  def cancel(): Unit
