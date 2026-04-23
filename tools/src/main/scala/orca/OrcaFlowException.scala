package orca

/** Exception type thrown by `fail(...)` and tool adapters. Signals that the
  * current flow cannot continue.
  */
class OrcaFlowException(message: String) extends RuntimeException(message)

/** Thrown by [[Conversation.result]] when the user cancels the current
  * interactive call. The enclosing `stage(...)` can catch this and decide
  * whether to fail the stage or recover — cancellation is a local
  * signal, not a flow-level abort.
  */
class OrcaInteractiveCancelled(
    message: String = "interactive session cancelled"
) extends OrcaFlowException(message)
