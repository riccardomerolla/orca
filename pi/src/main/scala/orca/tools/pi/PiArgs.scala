package orca.tools.pi

import orca.backend.CliArgs
import orca.llm.{BackendTag, LlmConfig, SessionId}

/** Maps Orca backend configuration to Pi CLI arguments. The backend drives Pi
  * through RPC mode and sends prompts over stdin, so the argv carries only
  * process/session/configuration flags.
  */
private[pi] object PiArgs:

  val ReadOnlyTools: Seq[String] = Seq("read", "grep", "find", "ls")

  def rpc(
      session: SessionId[BackendTag.Pi.type],
      config: LlmConfig,
      systemPromptFile: Option[os.Path],
      askUserExtension: Option[os.Path] = None
  ): Seq[String] =
    Seq(
      "pi",
      "--mode",
      "rpc",
      "--session",
      SessionId.value(session)
    ) ++
      CliArgs.modelArgs(config) ++
      systemPromptArgs(systemPromptFile) ++
      toolsArgs(config, askUserExtension.isDefined) ++
      extensionArgs(askUserExtension)

  private def systemPromptArgs(file: Option[os.Path]): Seq[String] =
    file.toSeq.flatMap(f => Seq("--append-system-prompt", f.toString))

  private def toolsArgs(
      config: LlmConfig,
      includeAskUser: Boolean
  ): Seq[String] =
    if !config.readOnly then Seq.empty
    else
      val tools =
        if includeAskUser then ReadOnlyTools :+ PiAskUserExtension.ToolName
        else ReadOnlyTools
      Seq("--tools", tools.mkString(","))

  private def extensionArgs(file: Option[os.Path]): Seq[String] =
    file.toSeq.flatMap(f => Seq("--extension", f.toString))
