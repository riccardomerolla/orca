package orca.cli

import _root_.orca.*

/** Production FlowContext wiring. `claude` and `codex` are placeholders until
  * their user-facing LlmTool implementations land — git, gh, and fs are already
  * connected to real implementations rooted at `workDir`.
  */
class DefaultFlowContext(
    val userPrompt: String,
    dispatcher: EventDispatcher,
    workDir: os.Path = os.pwd
) extends FlowContext:

  // TODO: wire real ClaudeTool / CodexTool implementations over the backends.
  def claude: ClaudeTool =
    throw new NotImplementedError("ClaudeTool not yet implemented")
  def codex: CodexTool =
    throw new NotImplementedError("CodexTool not yet implemented")

  val git: GitTool = new OsGitTool(workDir)
  val gh: GitHubTool = new OsGitHubTool(OsProcCliRunner, workDir)
  val fs: FsTool = new OsFsTool(workDir)

  def emit(event: OrcaEvent): Unit = dispatcher.dispatch(event)
