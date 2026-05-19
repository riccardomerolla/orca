package orca.review

import orca.llm.{BackendTag, LlmTool}
import orca.util.PromptResource

/** Canonical system prompts for the reviewers the library ships with. Exposed
  * so callers can tune or extend the set without rewriting the defaults.
  *
  * Source text lives in `src/main/resources/orca/review/prompts/reviewers/`.
  */
object ReviewerPrompts:
  val Performance: String =
    PromptResource.load("/orca/review/prompts/reviewers/performance.md")

  val Readability: String =
    PromptResource.load("/orca/review/prompts/reviewers/readability.md")

  val TestCoverage: String =
    PromptResource.load("/orca/review/prompts/reviewers/test-coverage.md")

  val CodeFunctionality: String =
    PromptResource.load("/orca/review/prompts/reviewers/code-functionality.md")

  val Abstraction: String =
    PromptResource.load("/orca/review/prompts/reviewers/abstraction.md")

/** Pre-configured reviewer agents built atop the supplied base tool. Each
  * reviewer has its own `name` and system prompt; callers pass them (or a
  * subset via `SelectedReviewers.pick`) to `reviewAndFixLoop`.
  */
def defaultReviewers[B <: BackendTag](base: LlmTool[B]): List[LlmTool[B]] =
  // Names are prefixed with `reviewer: ` so the per-agent token breakdown
  // groups every reviewer dimension together (matching `lint`, which the
  // review loop labels `reviewer: lint`). The non-reviewer driver agent
  // keeps its default name (`main`).
  def reviewer(name: String, prompt: String): LlmTool[B] =
    base.withSystemPrompt(prompt).withName(s"reviewer: $name")
  List(
    reviewer("performance", ReviewerPrompts.Performance),
    reviewer("readability", ReviewerPrompts.Readability),
    reviewer("test-coverage", ReviewerPrompts.TestCoverage),
    reviewer("code-functionality", ReviewerPrompts.CodeFunctionality),
    reviewer("abstraction", ReviewerPrompts.Abstraction)
  )
