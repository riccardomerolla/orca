package orca

import _root_.orca.io.DefaultPromptTemplate
import _root_.orca.runner.DefaultFlowContext
import _root_.orca.runner.terminal.TerminalInteraction
import ox.supervised

/** Entry point for flow scripts. Takes the parsed CLI args (required) plus
  * any number of overrides, then runs the body inside an Ox `supervised`
  * scope with the resulting `FlowContext` as an ambient given.
  *
  * ```
  * flow(OrcaArgs.from(args.toSeq)):
  *   val plan = claude.resultAs[Plan].autonomous(userPrompt)
  *   ...
  * ```
  *
  * With overrides:
  *
  * ```
  * flow(OrcaArgs.from(args.toSeq), git = Some(myGit), interaction = SlackInteraction(...)):
  *   ...
  * ```
  *
  * The two-parameter-list shape is deliberate: Scala 3's fewer-braces
  * propagates the `FlowContext ?=>` given into the body only when the
  * block lands in a list of its own. `flow(...): body` satisfies that
  * because the first list is closed by `(...)` before the block starts.
  */
def flow(
    args: OrcaArgs,
    interaction: Interaction = new TerminalInteraction(),
    extraListeners: List[OrcaListener] = Nil,
    workDir: os.Path = os.pwd,
    claude: Option[ClaudeTool] = None,
    git: Option[GitTool] = None,
    gh: Option[GitHubTool] = None,
    fs: Option[FsTool] = None,
    promptTemplate: PromptTemplate = DefaultPromptTemplate
)(body: FlowContext ?=> Unit): Unit =
  supervised:
    val dispatcher =
      new EventDispatcher(interaction.listeners ++ extraListeners)
    val ctx = DefaultFlowContext.withDefaults(
      userPrompt = args.userPrompt,
      dispatcher = dispatcher,
      workDir = workDir,
      interaction = interaction,
      claude = claude,
      git = git,
      gh = gh,
      fs = fs,
      template = promptTemplate
    )
    body(using ctx)
