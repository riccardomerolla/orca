package orca.cli

import _root_.orca.*
import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import sttp.tapir.Schema

/** Default implementation of LlmCall for the Claude backend. Only the headless
  * `prompt` path is wired today — session and interactive variants are stubbed
  * pending Epic 8 integration.
  */
class DefaultLlmCall[O](
    backend: LlmBackend[Backend.ClaudeCode.type],
    effectiveConfig: LlmConfig => LlmConfig,
    template: PromptTemplate,
    workDir: os.Path,
    emit: OrcaEvent => Unit
)(using schema: Schema[O], codec: ConfiguredJsonValueCodec[O])
    extends LlmCall[Backend.ClaudeCode.type, O]:

  def prompt[I: AgentInput](
      input: I,
      config: LlmConfig = LlmConfig.default
  ): O =
    val serialized = summon[AgentInput[I]].serialize(input)
    val outputSchema = JsonSchemaGen[O]
    val full = template.autonomous(serialized, outputSchema, config)
    val effective = effectiveConfig(config)
    val result = backend.runHeadless(full, effective, workDir)
    emit(OrcaEvent.TokensUsed(result.usage))
    ResponseParser.parse[O](result.output)

  def startSession[I: AgentInput](
      input: I,
      config: LlmConfig = LlmConfig.default
  ): (SessionId[Backend.ClaudeCode.type], O) = ???

  def continueSession[I: AgentInput](
      sessionId: SessionId[Backend.ClaudeCode.type],
      input: I,
      config: LlmConfig = LlmConfig.default
  ): O = ???

  def interactive[I: AgentInput](
      input: I,
      config: LlmConfig = LlmConfig.default
  ): (SessionId[Backend.ClaudeCode.type], O) = ???

  def continueInteractive[I: AgentInput](
      sessionId: SessionId[Backend.ClaudeCode.type],
      input: I,
      config: LlmConfig = LlmConfig.default
  ): O = ???
