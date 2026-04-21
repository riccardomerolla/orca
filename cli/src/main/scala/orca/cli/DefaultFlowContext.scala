package orca.cli

import _root_.orca.*
import _root_.orca.claude.ClaudeBackend

/** Production FlowContext wiring. Callers typically construct one via
  * `orca(...)`, which supplies defaults for all tools. Individual tools can be
  * replaced by passing overrides to `orca`. `codex` remains stubbed pending
  * Epic 9.
  */
class DefaultFlowContext(
    val userPrompt: String,
    dispatcher: EventDispatcher,
    val claude: ClaudeTool,
    val git: GitTool,
    val gh: GitHubTool,
    val fs: FsTool
) extends FlowContext:

  def codex: CodexTool =
    throw new NotImplementedError("CodexTool lands with Epic 9")

  def emit(event: OrcaEvent): Unit = dispatcher.dispatch(event)

object DefaultFlowContext:

  /** Build a context with Orca's default tool implementations, allowing callers
    * to override any individual tool.
    */
  def withDefaults(
      userPrompt: String,
      dispatcher: EventDispatcher,
      workDir: os.Path,
      claude: ClaudeTool = null,
      git: GitTool = null,
      gh: GitHubTool = null,
      fs: FsTool = null,
      template: PromptTemplate = DefaultPromptTemplate
  ): DefaultFlowContext =
    new DefaultFlowContext(
      userPrompt = userPrompt,
      dispatcher = dispatcher,
      claude = Option(claude).getOrElse(
        new DefaultClaudeTool(
          backend = new ClaudeBackend(OsProcCliRunner),
          config = LlmConfig.default,
          template = template,
          workDir = workDir,
          emit = dispatcher.dispatch
        )
      ),
      git = Option(git).getOrElse(new OsGitTool(workDir)),
      gh = Option(gh).getOrElse(new OsGitHubTool(OsProcCliRunner, workDir)),
      fs = Option(fs).getOrElse(new OsFsTool(workDir))
    )
