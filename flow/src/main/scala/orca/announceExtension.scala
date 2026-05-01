package orca

/** `value.announce` — a manual trigger for [[Announce]]. Lives in the flow
  * module (rather than alongside the typeclass in tools) because it depends on
  * `FlowContext`. The library's auto-announce path inside `DefaultLlmCall`
  * doesn't need the extension; it has the `emit` callback directly.
  */
extension [O](value: O)(using a: Announce[O])
  def announce(using ctx: FlowContext): Unit =
    a.message(value).foreach(msg => ctx.emit(OrcaEvent.Step(msg)))
