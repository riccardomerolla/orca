package orca.tools.claude

import orca.{
  Backend,
  ClaudeTool,
  Interaction,
  JsonData,
  LlmBackend,
  LlmCall,
  LlmConfig,
  OrcaEvent,
  PromptTemplate,
  SessionId
}

/** Default ClaudeTool implementation that routes calls through a
  * `LlmBackend[Backend.ClaudeCode.type]`. The tool is immutable; `withConfig`,
  * `withSystemPrompt`, and the model accessors return new instances so flow
  * scripts can chain without side effects.
  *
  * Headless calls (`ask`, `startSession`, `continueSession`, and the full
  * `resultAs[O]` shape) go straight through the backend. Interactive calls
  * spawn claude with an inherited TTY, then hand the terminal to
  * `interaction` so the `Interaction`-specific channel (terminal, Slack,
  * etc.) owns the user session until the agent writes its done marker.
  */
class DefaultClaudeTool(
    backend: LlmBackend[Backend.ClaudeCode.type],
    config: LlmConfig,
    template: PromptTemplate,
    workDir: os.Path,
    emit: OrcaEvent => Unit,
    interaction: Interaction
) extends ClaudeTool:

  val name: String = "claude"

  def haiku: ClaudeTool = withModel("claude-haiku-4-5")
  def sonnet: ClaudeTool = withModel("claude-sonnet-4-6")
  def opus: ClaudeTool = withModel("claude-opus-4-7")

  def withConfig(newConfig: LlmConfig): ClaudeTool =
    copy(config = newConfig)

  def withSystemPrompt(prompt: String): ClaudeTool =
    copy(config = config.copy(systemPrompt = Some(prompt)))

  def ask(prompt: String, callConfig: LlmConfig = LlmConfig.default): String =
    val effective = effectiveConfig(callConfig)
    val result = backend.runHeadless(prompt, effective, workDir)
    emit(OrcaEvent.TokensUsed(effective.model, result.usage))
    result.output

  def startSession(
      prompt: String,
      callConfig: LlmConfig = LlmConfig.default
  ): (SessionId[Backend.ClaudeCode.type], String) =
    val effective = effectiveConfig(callConfig)
    val result = backend.runHeadless(prompt, effective, workDir)
    emit(OrcaEvent.TokensUsed(effective.model, result.usage))
    (result.sessionId, result.output)

  def continueSession(
      sessionId: SessionId[Backend.ClaudeCode.type],
      prompt: String,
      callConfig: LlmConfig = LlmConfig.default
  ): String =
    val effective = effectiveConfig(callConfig)
    val result = backend.continueHeadless(sessionId, prompt, effective, workDir)
    emit(OrcaEvent.TokensUsed(effective.model, result.usage))
    result.output

  def resultAs[O: JsonData]: LlmCall[Backend.ClaudeCode.type, O] =
    new DefaultLlmCall[O](
      backend,
      effectiveConfig,
      template,
      workDir,
      emit,
      interaction
    )

  private def withModel(model: String): ClaudeTool =
    copy(config = config.copy(model = Some(model)))

  private def copy(
      backend: LlmBackend[Backend.ClaudeCode.type] = backend,
      config: LlmConfig = config,
      template: PromptTemplate = template,
      workDir: os.Path = workDir,
      emit: OrcaEvent => Unit = emit,
      interaction: Interaction = interaction
  ): DefaultClaudeTool =
    new DefaultClaudeTool(backend, config, template, workDir, emit, interaction)

  private def effectiveConfig(callConfig: LlmConfig): LlmConfig =
    // Call-level config overrides tool-level values where the call explicitly
    // set them. Detection is reference-based: a caller who omitted the arg
    // receives the shared LlmConfig.default singleton.
    if callConfig eq LlmConfig.default then config else callConfig
