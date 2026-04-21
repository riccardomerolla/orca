package orca.cli

import _root_.orca.*
import ox.supervised

/** Entry point for flow scripts. Creates a FlowContext, registers the
  * interaction's listeners plus any extras the caller provides, and runs `body`
  * inside an Ox `supervised` scope so any forked effects are tied to the flow's
  * lifetime.
  *
  * Most scripts use:
  * ```
  * orca:
  *   val plan = claude.result[Plan].prompt(userPrompt)
  *   ...
  * ```
  *
  * Pass a parsed `OrcaArgs` to receive `userPrompt`; pass custom `interaction`
  * / `listeners` to override the defaults.
  */
def orca(
    args: OrcaArgs = OrcaArgs(),
    interaction: Interaction = new TerminalInteraction(),
    extraListeners: List[OrcaListener] = Nil,
    workDir: os.Path = os.pwd
)(body: FlowContext ?=> Unit): Unit =
  supervised:
    val dispatcher =
      new EventDispatcher(interaction.listeners ++ extraListeners)
    val ctx = new DefaultFlowContext(args.userPrompt, dispatcher, workDir)
    body(using ctx)
