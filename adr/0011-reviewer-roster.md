# ADR 0011: Reviewer roster and aspects

## Context

`allReviewers` (and `ReviewerPrompts.all`) ships a fixed list of reviewer
agents that `reviewAndFixLoop` runs against every task. Each reviewer is
a paid LLM roundtrip; the LLM-driven selector narrows the active set per
task, but the *roster itself* still needs to be coherent — overlapping
reviewers waste tokens, missing dimensions leave gaps.

The initial roster was assembled ad-hoc from existing third-party agent
prompts. Several reviewers had broad mandates that overlapped (e.g.
backend-architect overlapped with code-structure and code-functionality);
the prompts were long, full of templated output formats and inline
examples that bloated the context every time a reviewer ran; and a few
dimensions weren't covered (security in particular, given that flow
scripts shell out to `git`, `gh`, and arbitrary `os.proc` calls).

## Decision

### Roster (7 reviewers)

Each reviewer covers a thematically connected group of aspects. The
groups are designed so a reviewer either applies to the change or
doesn't — the picker can skip cleanly rather than running a half-relevant
reviewer.

| Reviewer | Aspects |
|---|---|
| **code-functionality** | correctness vs. stated intent; edge cases (empty, boundary, null, malformed, unicode); failure modes; error propagation, logging context, observability; error swallowing; thread-safety of shared state |
| **test** | minimality; no duplicate tests; single property per test; coverage of new behaviour; edge-case exercise; setup clarity |
| **readability** | naming clarity; comments (why, not what); control-flow shape; magic values; local consistency |
| **code-structure** | duplication and abstraction quality; module boundaries; package/dependency direction (no cycles, no layering violations); symbol visibility (narrowest enclosing scope) |
| **performance** | algorithmic complexity; memory and allocations; I/O batching (n+1, pooling, overfetch); concurrency (races, deadlocks, ordering, cancellation); resource lifecycle |
| **security** | input validation; injection (shell/SQL/path/template); secret handling; unsafe deserialisation; privilege and authz; TLS/transport |
| **scala-fp** | no shared mutable state; pure functions, explicit deps; immutable data and ADTs; opaque types for domain values; no boolean blindness; failures as values; direct-style hygiene (braceless, `using Ox` discipline) |

Notes on the design:

- **Error handling lives in code-functionality.** Unhandled or swallowed
  errors are correctness bugs; bundling them avoids a separate reviewer
  that would overlap on every change.
- **Concurrency lives in performance.** Race conditions are correctness
  bugs *and* performance bugs, but the skill required to find them
  overlaps more with resource-lifecycle/scaling than with happy-path
  correctness.
- **Modularity lives in code-structure, not readability.** Readability is
  about how a reader experiences code line-by-line; code-structure is
  about how the pieces fit together. Naming and consistency can affect
  either, but the dominant axis is what defines the reviewer.
- **scala-fp is language-specific.** It only runs on Scala changes;
  applying it to non-Scala code wastes tokens.

### Reviewers dropped

- **backend-architect**: overlapped with code-structure (design,
  abstractions) and code-functionality (correctness, error handling).
  The remaining unique territory (architectural-pattern
  recommendations) is mostly project-specific and rarely actionable on
  a per-task review.
- **abstraction**: folded into **code-structure**, expanded to include
  modularity, module boundaries, dependency direction, and visibility.

### Prompt format

Every reviewer prompt is a `.md` file with YAML frontmatter
(`name`, `description`) and a body. Conventions:

- **Description**: one or two sentences. It feeds the LLM-driven
  selector, which compares it against the task title and changed files.
  Keep it specific enough that the picker can distinguish reviewers, but
  short — every reviewer's description is sent on every selection call.
- **Body**: one introductory line, a bulleted list of aspects under a
  single `## Aspects` heading, and a short `## Output` section.
  No examples, no templated output formats, no roleplay framing.
  Targeting ~25 lines of body so the system prompt stays small.
- **Negative scope** in the closing line: state what the reviewer should
  *not* review (e.g. "Do not review style, naming, performance, or test
  quality unless the issue directly affects correctness."). This keeps
  reviewers from drifting into each other's territory and producing
  duplicate findings.

## Consequences

**Positive**

- Each task's selector picks a typically-small subset (2–4 reviewers);
  the trimmed roster keeps the picker's job tractable.
- Compact prompts reduce per-review context cost (~20% per reviewer vs.
  the previous templated form).
- Security is now covered — closes the obvious gap given that flow
  scripts shell out by design.

**Negative**

- Removing backend-architect loses architectural-pattern advice. For
  larger refactors users can compose a custom reviewer list including
  their own backend-architect-style prompt.
- The current `minimal` preset (`CodeFunctionality`, `Readability`,
  `Test`) still matches its purpose — a small set for low-stakes diffs.
- Folding modularity into code-structure means a single reviewer can
  produce wide-ranging feedback (duplication *and* visibility *and*
  layering). Acceptable: they're the same skill ("zoom out on the
  diff"), and the negative-scope line keeps it from drifting further.
