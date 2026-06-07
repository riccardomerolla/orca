package orca.tools.pi

import orca.backend.{Interaction, LlmBackend}
import orca.events.OrcaListener
import orca.llm.{BackendTag, LlmConfig, PiTool, Prompts}
import orca.llm.BaseLlmTool

/** Default [[PiTool]] implementation. Inherits the autonomous-text and
  * structured-output plumbing from [[BaseLlmTool]]; Pi model selection is left
  * to generic [[LlmConfig.model]] values because Pi supports many providers and
  * fuzzy model patterns through its own CLI.
  */
private[orca] class DefaultPiTool(
    backend: LlmBackend[BackendTag.Pi.type],
    config: LlmConfig,
    prompts: Prompts,
    workDir: os.Path,
    events: OrcaListener,
    interaction: Interaction,
    val name: String = "pi"
) extends BaseLlmTool[BackendTag.Pi.type, PiTool](
      backend,
      config,
      prompts,
      workDir,
      events,
      interaction
    )
    with PiTool:

  protected def copyTool(
      config: LlmConfig = config,
      name: String = name
  ): PiTool =
    new DefaultPiTool(
      backend,
      config,
      prompts,
      workDir,
      events,
      interaction,
      name
    )
