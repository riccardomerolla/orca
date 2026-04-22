package orca

import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import sttp.tapir.Schema

trait LlmTool[B <: Backend]:
  def name: String
  def result[O: Schema: ConfiguredJsonValueCodec]: LlmCall[B, O]
  def ask(prompt: String, config: LlmConfig = LlmConfig.default): String
  def withConfig(config: LlmConfig): LlmTool[B]
  def withSystemPrompt(prompt: String): LlmTool[B]

trait ClaudeTool extends LlmTool[Backend.ClaudeCode.type]:
  def haiku: ClaudeTool
  def sonnet: ClaudeTool
  def opus: ClaudeTool

trait CodexTool extends LlmTool[Backend.Codex.type]:
  def mini: CodexTool

trait LlmCall[B <: Backend, O]:
  def prompt[I: AgentInput](input: I, config: LlmConfig = LlmConfig.default): O
  def startSession[I: AgentInput](
      input: I,
      config: LlmConfig = LlmConfig.default
  ): (SessionId[B], O)
  def continueSession[I: AgentInput](
      sessionId: SessionId[B],
      input: I,
      config: LlmConfig = LlmConfig.default
  ): O
  def interactive[I: AgentInput](
      input: I,
      config: LlmConfig = LlmConfig.default
  ): (SessionId[B], O)
  def continueInteractive[I: AgentInput](
      sessionId: SessionId[B],
      input: I,
      config: LlmConfig = LlmConfig.default
  ): O
