package orca

// Re-exports the user-facing surface from `orca.plan`, `orca.bug`,
// `orca.review`, and `ox.either` so flow scripts can pull everything in
// with a single `import orca.{*, given}`. The list mirrors what the
// README documents as the public API; deliberately omits the
// customisation-only `*Prompts` objects so that `instructions =
// orca.plan.PlanPrompts.Planning + ...` stays self-documenting at the
// call site rather than fading into the wildcard.

export orca.plan.{Plan, Task}
export orca.bug.{BugTriage, BugReportMatch}
export orca.review.{
  defaultReviewers,
  fixLoop,
  lint,
  reviewAndFixLoop,
  FixOutcome,
  ReviewIssue,
  ReviewResult
}
export ox.either.orThrow
