package orca.claude

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import orca.*
import ox.Ox

class ClaudeBackend(cli: CliRunner) extends LlmBackend[Backend.ClaudeCode.type]:

  def prepareWorkspace(
      config: LlmConfig,
      outputSchema: String,
      workDir: os.Path
  )(using Ox): Unit = ???

  def runHeadless(
      prompt: String,
      config: LlmConfig,
      workDir: os.Path
  ): LlmResult[Backend.ClaudeCode.type] =
    invokeHeadless(prompt, config, workDir, resume = None)

  def continueHeadless(
      sessionId: SessionId[Backend.ClaudeCode.type],
      prompt: String,
      config: LlmConfig,
      workDir: os.Path
  ): LlmResult[Backend.ClaudeCode.type] =
    invokeHeadless(prompt, config, workDir, resume = Some(sessionId))

  def launchInteractive(
      prompt: String,
      config: LlmConfig,
      workDir: os.Path
  ): InteractiveHandle[Backend.ClaudeCode.type] = ???

  def resumeInteractive(
      sessionId: SessionId[Backend.ClaudeCode.type],
      prompt: String,
      config: LlmConfig,
      workDir: os.Path
  ): InteractiveHandle[Backend.ClaudeCode.type] = ???

  private def invokeHeadless(
      prompt: String,
      config: LlmConfig,
      workDir: os.Path,
      resume: Option[SessionId[Backend.ClaudeCode.type]]
  ): LlmResult[Backend.ClaudeCode.type] =
    val systemPromptFile = writeSystemPromptIfPresent(config, workDir)
    val args = ClaudeArgs.headless(prompt, config, systemPromptFile, resume)
    val result = cli.run(args, cwd = workDir)
    if result.exitCode != 0 then
      val diagnostic =
        if result.stderr.nonEmpty then result.stderr else result.stdout
      throw OrcaFlowException(
        s"claude CLI failed (exit ${result.exitCode}): $diagnostic"
      )
    val response = readFromString[ClaudeHeadlessResponse](result.stdout)
    if response.is_error.contains(true) then
      throw OrcaFlowException(s"claude reported an error: ${response.result}")
    response.toLlmResult

  private def writeSystemPromptIfPresent(
      config: LlmConfig,
      workDir: os.Path
  ): Option[os.Path] =
    config.systemPrompt.map: body =>
      val file = workDir / ".claude" / "orca-system-prompt.md"
      os.write.over(file, body, createFolders = true)
      file
