Task: {{task}}

Review the following changes only — do NOT survey unrelated files in the
project. Focus your findings strictly on what the diff modifies and on code that
interacts directly with it.

Diff (working tree vs HEAD at the start of the review loop):

{{diffBlock}}

Report each finding with: severity (Critical / Warning / Info), a one-line
title, a longer description with enough context for a fixer to act, the file and
line where applicable, and a concrete suggested fix. If nothing in your scope
applies to this change, report no issues.