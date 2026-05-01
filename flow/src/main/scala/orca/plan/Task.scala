package orca.plan

import orca.JsonData

/** A single task in a development plan, shared by [[orca.plan.simple]]
  * (in-memory) and [[orca.plan.extended]] (markdown-backed) plan
  * variants.
  *
  *   - `name` is a kebab-case identifier. Simple plans use it as the
  *     git branch name; extended plans use it as the markdown section
  *     header (`## Task: <name>`).
  *   - `shortSummary` is the one-line human-readable label rendered in
  *     the event log. Extended plans without an explicit summary in
  *     markdown derive it from `name` ("add-divide" → "Add divide").
  *   - `description` is the longer instruction handed to the
  *     implementing agent.
  *   - `completed` is the resume-state checkbox used by extended
  *     plans. Simple plans run end-to-end and leave it false.
  */
case class Task(
    name: String,
    shortSummary: String,
    description: String,
    completed: Boolean = false
) derives JsonData:
  def markComplete: Task = copy(completed = true)
