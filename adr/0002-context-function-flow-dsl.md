# 0002. Flow scripts use a context-function DSL with an ambient FlowContext

Status: Accepted · Date: 2026-04-22 (updated 2026-04-23)

## Decision

The user writes flow scripts inside a `flow:` block:

```scala
flow:
  val plan = claude.resultAs[Plan].autonomous(userPrompt)
  git.createBranch(plan.branchName)
  ...
```

`flow` takes a `FlowContext ?=> Unit` — a Scala 3 context function — and
top-level `def claude(using FlowContext): ClaudeTool`, `def git(using
FlowContext): GitTool`, `def userPrompt(using FlowContext): String`, etc.
resolve the ambient context implicitly. For custom wiring,
`flowWith(args = ..., git = ..., interaction = ...)(body)` takes the
overrides and hands control to the same context-function body.

## Why

- Users don't pass `ctx` to every helper call; the ergonomics match a
  script, not a service.
- Swapping the entire runtime (Slack instead of terminal, custom git,
  mocked Claude) is `flowWith(interaction = ..., git = ..., claude = ...)` —
  no wrapping, no subclassing.
- The compiler enforces that helpers like `stage`, `fail`, `fixLoop` can
  only be called where a `FlowContext` is in scope.

## Consequences

- Flow helpers are top-level `def`s taking `(using FlowContext)`, not
  methods on a base class.
- Testing a flow requires a `TestFlowContext` that stubs tools lazily —
  we ship one for library tests.
- `import orca.{*, given}` pulls in the entry function, the accessors,
  the helpers, the data types, and the JsonData forwarder givens in a
  single line. The `given` selector is required because Scala 3 excludes
  given instances from plain wildcard imports, and the forwarders bridge
  `derives JsonData` to the underlying Schema/codec needed during nested
  macro derivation.
- `flow` is deliberately a single-argument method (body only), because
  Scala 3's `flow: <block>` fewer-braces syntax only propagates the
  `FlowContext ?=>` given when the body is the sole argument. A two-list
  or overloaded signature silently loses the given inside the body.
  `flowWith(...)` takes the configurable path and uses the two-list
  shape; its `:block` form only works with an explicit argument list
  (e.g. `flowWith(git = ...): body`), which is fine for the configured
  call site.
