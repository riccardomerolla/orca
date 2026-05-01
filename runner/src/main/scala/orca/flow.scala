package orca

import _root_.orca.io.DefaultPrompts
import _root_.orca.runner.DefaultFlowContext
import _root_.orca.runner.terminal.TerminalInteraction
import ox.supervised

import scala.util.control.NonFatal

/** Entry point for flow scripts. Takes the parsed CLI args (required) plus any
  * number of overrides, then runs the body with a `FlowContext` as an ambient
  * given so the DSL accessors (`claude`, `git`, etc.) resolve.
  *
  * ```
  * flow(OrcaArgs(args)):
  *   val plan = claude.resultAs[Plan].autonomous(userPrompt)
  *   ...
  * ```
  *
  * Override any tool by passing it as a named argument in the first list:
  *
  * ```
  * flow(
  *   OrcaArgs(args),
  *   git = Some(myGit),
  *   interaction = Some(SlackInteraction(...))
  * ):
  *   ...
  * ```
  *
  * Overrides default to `None` so the runtime can build the default lazily —
  * `TerminalInteraction`, in particular, takes the resolved `workDir` which
  * can't be threaded through a Scala 3 default-arg expression.
  */
def flow(
    args: OrcaArgs,
    workDir: os.Path = os.pwd,
    interaction: Option[Interaction] = None,
    extraListeners: List[OrcaListener] = Nil,
    claude: Option[ClaudeTool] = None,
    git: Option[GitTool] = None,
    gh: Option[GitHubTool] = None,
    fs: Option[FsTool] = None,
    prompts: Prompts = DefaultPrompts
)(body: FlowContext ?=> Unit): Unit =
  val debug = OrcaDebug.enabled || args.verbose.value
  // Default to a TerminalInteraction parameterised with the resolved
  // `workDir` — Scala 3 default-arg evaluation can't see prior params
  // in the same list, so the substitution happens here instead.
  val effectiveInteraction = interaction.getOrElse(
    new TerminalInteraction(workDir = Some(workDir))
  )
  try
    supervised:
      val dispatcher =
        new EventDispatcher(effectiveInteraction.listeners ++ extraListeners)
      val ctx = DefaultFlowContext.withDefaults(
        userPrompt = args.userPrompt,
        dispatcher = dispatcher,
        workDir = workDir,
        interaction = effectiveInteraction,
        claude = claude,
        git = git,
        gh = gh,
        fs = fs,
        prompts = prompts
      )
      body(using ctx)
  catch
    // Stage-level Errors have already been emitted through the channel.
    // The outer `catch` exists to suppress the raw JVM stack trace on
    // exit — the user has already seen a formatted message. With
    // `ORCA_DEBUG=1` or `--verbose` we do print the trace for
    // diagnostics.
    case NonFatal(e) =>
      if debug then e.printStackTrace()
      System.exit(1)
