package orca.review

import orca.llm.{JsonData, LlmTool}

case class SelectedReviewers(names: List[String]) derives JsonData:
  /** Resolve the picker's reply to tools. Matches either the bare slug (what
    * the picker is shown and is asked to echo) or the full `reviewer: <slug>`
    * tool name, so a model that returns either form still resolves — the
    * `reviewer: ` cost-attribution prefix must not gate selection.
    */
  def pick(all: List[LlmTool[?]]): List[LlmTool[?]] =
    all.filter: r =>
      val slug = ReviewerPrompts.stripNamePrefix(r.name)
      names.contains(r.name) || names.contains(slug)
