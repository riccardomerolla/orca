package orca.tools.pi

import orca.backend.CliArgs
import orca.llm.LlmConfig

/** Maps Orca backend configuration to Pi CLI arguments. The backend drives Pi
  * through RPC mode and sends prompts over stdin, so the argv carries only
  * process/session/configuration flags.
  *
  * Session continuity uses Pi's on-disk sessions: `--session-dir` points at a
  * directory Pi creates a fresh session in on the first turn; `resume` adds
  * `--continue` so a later turn picks up the prior session in that dir. (Pi's
  * `--session <id>` only *resumes* an existing id, so it can't seed a
  * caller-chosen id for a new session.)
  */
private[pi] object PiArgs:

  val ReadOnlyTools: Seq[String] = Seq("read", "grep", "find", "ls")

  def rpc(
      sessionDir: os.Path,
      resume: Boolean,
      config: LlmConfig,
      systemPromptFile: Option[os.Path],
      askUserExtension: Option[os.Path] = None
  ): Seq[String] =
    Seq("pi", "--mode", "rpc", "--session-dir", sessionDir.toString) ++
      Option.when(resume)("--continue").toSeq ++
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
