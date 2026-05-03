package orca.plan

import orca.{
  AgentInput,
  Announce,
  Backend,
  JsonData,
  LlmCall,
  LlmConfig,
  LlmTool,
  SessionId
}

/** Test double whose `resultAs[Plan].autonomous` returns a pre-built `Plan`.
  * Other call shapes throw so accidental use surfaces immediately. The plan is
  * the only structured payload Plan-related tests need; supporting just that
  * shape keeps the fixture small.
  */
private[plan] class CannedPlanLlm(plan: Plan)
    extends LlmTool[Backend.ClaudeCode.type]:
  val name: String = "stub"
  def ask(p: String, c: LlmConfig = LlmConfig.default): String = ???
  def startSession(p: String, c: LlmConfig = LlmConfig.default) = ???
  def continueSession(
      s: SessionId[Backend.ClaudeCode.type],
      p: String,
      c: LlmConfig = LlmConfig.default
  ) = ???
  def withConfig(c: LlmConfig): LlmTool[Backend.ClaudeCode.type] = this
  def withSystemPrompt(p: String): LlmTool[Backend.ClaudeCode.type] = this
  def withName(n: String): LlmTool[Backend.ClaudeCode.type] = this

  def resultAs[O: JsonData: Announce]: LlmCall[Backend.ClaudeCode.type, O] =
    new LlmCall[Backend.ClaudeCode.type, O]:
      def autonomous[I](input: I, config: LlmConfig = LlmConfig.default)(using
          AgentInput[I]
      ): O = plan.asInstanceOf[O]
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

/** Test double that throws on every method — used to assert that a code path
  * doesn't call the LLM.
  */
private[plan] class ExplodingLlm(reason: String)
    extends LlmTool[Backend.ClaudeCode.type]:
  val name: String = "exploding"
  def ask(p: String, c: LlmConfig = LlmConfig.default): String =
    throw new AssertionError(reason)
  def startSession(p: String, c: LlmConfig = LlmConfig.default) =
    throw new AssertionError(reason)
  def continueSession(
      s: SessionId[Backend.ClaudeCode.type],
      p: String,
      c: LlmConfig = LlmConfig.default
  ) =
    throw new AssertionError(reason)
  def withConfig(c: LlmConfig): LlmTool[Backend.ClaudeCode.type] = this
  def withSystemPrompt(p: String): LlmTool[Backend.ClaudeCode.type] = this
  def withName(n: String): LlmTool[Backend.ClaudeCode.type] = this
  def resultAs[O: JsonData: Announce]: LlmCall[Backend.ClaudeCode.type, O] =
    throw new AssertionError(reason)
