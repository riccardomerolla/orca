package orca

/** Exception type thrown by `fail(...)` and tool adapters. Signals that the
  * current flow cannot continue.
  */
class OrcaFlowException(message: String) extends RuntimeException(message)
