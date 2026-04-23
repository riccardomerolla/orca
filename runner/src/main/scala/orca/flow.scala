package orca

import _root_.orca.io.DefaultPromptTemplate
import _root_.orca.runner.DefaultFlowContext
import _root_.orca.runner.terminal.TerminalInteraction
import ox.supervised

/** Bare entry point for flow scripts. Creates a default-configured
  * FlowContext, registers the terminal interaction's listeners, and runs
  * `body` inside an Ox `supervised` scope.
  *
  * ```
  * flow:
  *   val plan = claude.resultAs[Plan].autonomous(userPrompt)
  *   ...
  * ```
  *
  * Kept to a single parameter on purpose: Scala 3's `flow: <block>`
  * fewer-braces syntax only propagates the `FlowContext ?=>` given when
  * the block is the sole argument. Overloads or a second parameter list
  * silently drop the given from the body. For custom wiring use
  * `flowWith(...)` — see below.
  */
def flow(body: FlowContext ?=> Unit): Unit = flowWith()(body)

/** Configured entry point. Override any of the tools, the interaction
  * channel, the working directory, etc. Every parameter is defaulted, so
  * callers only name the ones they change.
  *
  * ```
  * flowWith(args = OrcaArgs("ship it"), git = Some(myGit)):
  *   ...
  * ```
  *
  * The two-parameter-list shape is deliberate — it means the block
  * argument lands in a list of its own, which keeps the
  * `FlowContext ?=>` given propagating into the body. `flowWith: body`
  * (no parens) does not work; use plain `flow: body` for that case.
  */
def flowWith(
    args: OrcaArgs = OrcaArgs(),
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
