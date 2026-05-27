package orca.backend

import orca.llm.LlmConfig

class SystemPromptComposerTest extends munit.FunSuite:

  test("returns None when neither config nor hint is supplied"):
    val out = SystemPromptComposer.combine(LlmConfig.default, None)
    assertEquals(out, None)

  test("returns Some(systemPrompt) when only the config is supplied"):
    val out = SystemPromptComposer.combine(
      LlmConfig.default.copy(systemPrompt = Some("be terse")),
      extraHint = None
    )
    assertEquals(out, Some("be terse"))

  test("returns Some(hint) when only the extra hint is supplied"):
    val out = SystemPromptComposer.combine(
      LlmConfig.default,
      extraHint = Some("the hint")
    )
    assertEquals(out, Some("the hint"))

  test("joins config + hint with a blank line when both are supplied"):
    // Pins the separator (\\n\\n) — backends downstream rely on a blank
    // line between pieces so the agent reads them as distinct paragraphs.
    val out = SystemPromptComposer.combine(
      LlmConfig.default.copy(systemPrompt = Some("be terse")),
      extraHint = Some("the hint")
    )
    assertEquals(out, Some("be terse\n\nthe hint"))
