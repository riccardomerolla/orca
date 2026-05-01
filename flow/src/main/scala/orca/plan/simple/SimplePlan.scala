package orca.plan.simple

import orca.{Announce, JsonData, given}
import orca.plan.Task

/** A list of tasks the agent should work through in order. Plans stored on disk
  * use a richer markdown-backed representation; see [[orca.plan.extended]] for
  * that.
  *
  * The "simple" variant fits in one LLM round-trip: the agent produces the
  * JSON; the runtime parses it; the flow iterates. Each task's `name`
  * doubles as the git branch name created for that task.
  */
case class SimplePlan(tasks: List[Task]) derives JsonData

object SimplePlan:
  /** Empty plans render as nothing — surfacing "0 tasks planned" muddies
    * the picture; a planning failure is more useful as an explicit
    * `fail(...)` from the script.
    */
  given Announce[SimplePlan] = Announce.from: plan =>
    if plan.tasks.isEmpty then ""
    else
      val plural = if plan.tasks.size == 1 then "" else "s"
      val header =
        s"Planned ${plan.tasks.size} task$plural on branch '${plan.tasks.head.name}':"
      val body = plan.tasks.map(t => s"  - ${t.shortSummary}").mkString("\n")
      s"$header\n$body"
