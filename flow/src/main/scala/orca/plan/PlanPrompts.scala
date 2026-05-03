package orca.plan

/** Default prompt fragments for the planning helpers. Each `val` is a complete
  * instruction block that the helper appends to the user's request. Override by
  * passing a different string to the helper's `instructions` parameter — wrap
  * one of these defaults if you only want to extend the boilerplate:
  *
  * {{{
  * plan(userPrompt, claude,
  *   instructions = PlanPrompts.Planning + "\n\nFocus on observability tasks first.")
  * }}}
  */
object PlanPrompts:

  /** Used by [[plan]] for the interactive planning round-trip. Tells the agent
    * that this turn is plan-only and the implementation comes later — without
    * this guard the model frequently slides into editing files mid-plan.
    */
  val Planning: String =
    """Your job in this turn is to produce a development plan only — a
      |list of tasks broken down to a useful granularity. Do NOT edit
      |any files, do NOT write any code, and do NOT run build / test
      |commands. The plan is an outline; the implementation happens in
      |a separate later turn, task by task.""".stripMargin

  /** Used by [[Plan.loadOrGenerate]] to brief the planner LLM that it's
    * generating the on-disk markdown directly. References
    * [[Plan.SchemaDescription]] inline so callers who override this string can
    * still rely on the format spec.
    */
  val Generate: String =
    s"""Produce a development plan in the markdown format below.
       |Reply with ONLY the markdown — no surrounding prose, no
       |code fences.
       |
       |${Plan.SchemaDescription}""".stripMargin
