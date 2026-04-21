package orca

/** Synchronously forwards every `OrcaEvent` to a fixed list of listeners in
  * registration order. Listener exceptions propagate — an observation layer
  * that throws is almost certainly a bug, and silent swallowing would hide it
  * from the flow author.
  */
class EventDispatcher(listeners: List[OrcaListener]):
  def dispatch(event: OrcaEvent): Unit =
    listeners.foreach(_.onEvent(event))
