package orca

/** The channel that connects a flow to its user. `listeners` are registered on
  * the event dispatcher so the channel can show stage progress, streamed
  * output, token totals, and errors; `runInteractive` is invoked when a stage
  * hands control to the agent and blocks until the agent releases it, then
  * returns the `LlmResult` so the caller can parse the structured payload.
  * `TerminalInteraction` is the default; a Slack or HTTP implementation can be
  * substituted by passing `interaction = ...` to `flow(...)`.
  */
trait Interaction:
  def listeners: List[OrcaListener]
  def runInteractive[B <: Backend](handle: InteractiveHandle[B]): LlmResult[B]
