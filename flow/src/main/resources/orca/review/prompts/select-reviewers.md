Pick the subset of `availableReviewers` whose dimension is most
relevant to this task — judging by the title and the changed
files. Skip reviewers whose dimension clearly doesn't apply.

Honour scope constraints in each reviewer's description:

- A reviewer marked language-specific (e.g. "SCALA-ONLY") must be
  dropped when no files of that language appear in `changedFiles`.
  Look at the file extensions (`.scala`, `.rs`, `.java`, `.py`,
  …) — if none match the reviewer's language, it cannot find
  anything useful.
- A test-coverage reviewer is irrelevant when only docs/config
  changed; a security reviewer is unlikely to fire on a pure
  refactor with no I/O surface; etc.

Reply with a SelectedReviewers containing only names from
`availableReviewers`.