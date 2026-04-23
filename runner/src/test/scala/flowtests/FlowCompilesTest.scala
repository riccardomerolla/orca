package flowtests

// This file deliberately lives outside the `orca.*` package tree — it's
// the canary for the user-facing DSL. A third-party flow script sees
// exactly what this file sees: top-level types, accessors, and givens
// brought in by `import orca.{*, given}` and nothing else. The `given`
// selector is non-negotiable: Scala 3's plain `import orca.*` excludes
// givens, and the forwarders in `JsonData.scala` are what let nested
// `derives JsonData` find the child Schema during derivation.
//
// Each `def` targets one DSL concern so compile errors localise to the
// affected surface. Nothing here is invoked at runtime; `sbt test`
// simply requires the file to typecheck.
//
// If this file stops compiling, some aspect of the DSL contract has
// regressed. Fix the API, not the test.

import orca.{*, given}

case class PlanTask(branchName: String, description: String) derives JsonData
case class FlowPlan(tasks: List[PlanTask]) derives JsonData

object FlowCanary:

  /** Structured output via `derives JsonData` must be reachable through the
    * `resultAs[O]` path without any extra imports.
    */
  def structuredResult(): Unit =
    flow:
      stage("plan"):
        val (_, _) = claude.resultAs[FlowPlan].interactive(userPrompt)
        val _ = claude.resultAs[FlowPlan].autonomous(userPrompt)

  /** Free-form text prompts and session continuation; the shape the README
    * promises for per-task implementation.
    */
  def continuedSession(): Unit =
    flow:
      stage("impl"):
        val (sessionId, _) = claude.startSession("kick off")
        val _ = claude.continueSession(sessionId, "keep going")
        val _ = claude.ask("one-shot")

  /** Every top-level accessor must resolve from `import orca.*` alone.
    */
  def accessors(): Unit =
    flow:
      stage("tools"):
        val _ = git.createBranch("x")
        git.commit("msg")
        val _ = gh
        val _ = fs
        val _ = codex
        val _ = userPrompt

  /** Review-and-fix loop; pulls in `defaultReviewers` and the internal
    * `stage`/fork machinery.
    */
  def reviewLoop(): Unit =
    flow:
      val (sessionId, plan) = stage("plan"):
        claude.resultAs[FlowPlan].interactive(userPrompt)
      for task <- plan.tasks do
        stage(task.description):
          reviewAndFixLoop(
            coder = claude,
            sessionId = sessionId,
            reviewers = defaultReviewers(claude),
            task = task.description,
            lintCommand = Some("mvn -q test")
          )

  /** Config overrides must be reachable as unqualified names so users can
    * write `flowWith(args = OrcaArgs(...), workDir = ...)` straight from
    * `import orca.*`.
    */
  def configured(): Unit =
    flowWith(args = OrcaArgs("hello"), workDir = os.pwd):
      stage("cfg"):
        val _ = claude.ask(userPrompt)
