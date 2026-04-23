package orca.tools.claude

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import orca.{
  Backend,
  Conversation,
  LlmBackend,
  LlmConfig,
  LlmResult,
  OrcaFlowException,
  SessionId
}
import orca.subprocess.CliRunner
import orca.tools.claude.streamjson.OutboundMessage

/** Claude Code backend. Headless calls go through `claude -p --output-format
  * json` — single-shot, parses the JSON result. Interactive calls drive a
  * stream-json subprocess through [[ClaudeConversation]]: the prompt is
  * injected as the first user turn on stdin, the subprocess emits
  * typed NDJSON responses, the driver translates them into
  * `ConversationEvent`s the channel renders.
  */
class ClaudeBackend(cli: CliRunner) extends LlmBackend[Backend.ClaudeCode.type]:

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

  def runInteractive(
      prompt: String,
      config: LlmConfig,
      workDir: os.Path,
      outputSchema: Option[String]
  ): Conversation[Backend.ClaudeCode.type] =
    openConversation(prompt, config, workDir, resume = None, outputSchema)

  def continueInteractive(
      sessionId: SessionId[Backend.ClaudeCode.type],
      prompt: String,
      config: LlmConfig,
      workDir: os.Path,
      outputSchema: Option[String]
  ): Conversation[Backend.ClaudeCode.type] =
    openConversation(
      prompt,
      config,
      workDir,
      resume = Some(sessionId),
      outputSchema
    )

  /** Spawn `claude` in stream-json mode, write the opening user turn,
    * and wrap the process in a live [[ClaudeConversation]].
    *
    * If the initial write fails (claude exec'd then died, broken
    * pipe, etc.) we SIGINT the process before surfacing the error so
    * no subprocess leaks.
    */
  private def openConversation(
      prompt: String,
      config: LlmConfig,
      workDir: os.Path,
      resume: Option[SessionId[Backend.ClaudeCode.type]],
      outputSchema: Option[String]
  ): Conversation[Backend.ClaudeCode.type] =
    val systemPromptFile = writeSystemPromptIfPresent(config, workDir)
    val args =
      ClaudeArgs.streamJson(config, systemPromptFile, resume, outputSchema)
    val process = cli.spawnPiped(args, cwd = workDir)
    try
      process.writeLine(OutboundMessage.toJson(OutboundMessage.UserText(prompt)))
      new ClaudeConversation(process, config)
    catch
      case e: Exception =>
        process.sendSigInt()
        throw OrcaFlowException(
          s"Failed to open claude stream-json session: ${e.getMessage}"
        )

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
