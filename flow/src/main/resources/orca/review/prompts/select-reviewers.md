Pick the subset of `availableReviewers` whose dimension is relevant to this
task, judging by the title and the changed files. The goal is to skip the
reviewers that clearly don't apply, not to run them all. Reply with a
SelectedReviewers whose `names` are copied verbatim from the `name` field of
`availableReviewers` (one entry per chosen reviewer). When some reviewers
apply, don't return an empty list.
