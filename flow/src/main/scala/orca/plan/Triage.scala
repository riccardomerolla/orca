package orca.plan

import orca.llm.{Announce, BackendTag, SessionId}

/** Outcome of triaging a bug report against a codebase. Three variants:
  *
  *   - [[Triage.NotABug]] — intended behavior, user error, or out-of-scope.
  *     The flow surfaces `explanation` back on the original issue and stops.
  *   - [[Triage.Untestable]] — a real bug, but CI can't host a focused
  *     reproduction (UI-only, races, environment-specific). The flow posts
  *     `reproductionSteps` on the issue and stops; no PR.
  *   - [[Triage.Testable]] — a real bug with a CI-runnable reproduction. The
  *     flow lands a failing test at `failingTestPath`, opens a PR on
  *     `branchName`, then implements the fix.
  *
  * Each variant carries exactly the fields its branch needs, so callers
  * pattern-match instead of guarding the wide-record [[BugTriage]] wire
  * format with runtime `Option#get` / empty-string checks.
  *
  * Produced by [[Plan.interactive.triage]].
  */
enum Triage:
  case NotABug(explanation: String)
  case Untestable(summary: String, reproductionSteps: String)
  case Testable(summary: String, branchName: String, failingTestPath: String)

object Triage:
  given Announce[Triage] = Announce.from:
    case Triage.NotABug(explanation) => s"Not a bug: $explanation"
    case Triage.Untestable(summary, _) =>
      s"Triage: $summary — documenting reproduction (no PR)"
    case Triage.Testable(summary, branch, path) =>
      s"Triage: $summary — failing test at $path on branch '$branch'"

/** Result of an interactive triage round-trip: the [[Triage]] verdict plus
  * the implementation session the agent was running in. Carrying the session
  * in a typed field (rather than a positional tuple slot) makes the
  * "reusable for the downstream implementation phase" contract explicit at
  * the call site, where flow scripts destructure:
  *
  * {{{
  * val Triaged(session, triage) = Plan.interactive.triage(...)
  * }}}
  */
case class Triaged[B <: BackendTag](
    sessionId: SessionId[B],
    triage: Triage
)

object Triaged:
  given [B <: BackendTag]: Announce[Triaged[B]] =
    Announce.fromOption: t =>
      summon[Announce[Triage]].message(t.triage)
