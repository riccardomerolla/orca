# 0005. Reshape the flow DSL so `import orca.{*, given}` is the whole ceremony

Status: Accepted · Date: 2026-04-23

## Decision

Three related changes land together so a flow script is one import plus a
bare `flow:` block:

1. **Rename the entry method `orca` → `flow`**, kept to a single
   parameter (the body). Configured runs go through
   `flowWith(args = ..., git = ..., ...)(body)`.
2. **Move backend implementations under `orca.tools.<capability>`** —
   `orca.tools.claude`, `orca.tools.codex`, `orca.tools.fs`,
   `orca.tools.git`, `orca.tools.github`. Top-level `orca` is reserved
   for the user-facing surface (traits, accessors, `flow`/`flowWith`,
   `JsonData`, `OrcaArgs`).
3. **Rename `LlmTool.result[O]` to `resultAs[O]` and retype it to
   `[O: JsonData]`** — the new name reads as a verb at the call site
   (`claude.resultAs[Plan].autonomous(...)`), the new bound keeps
   `JsonData` as the single typeclass users ever need to know about.
   `AgentInput`'s JSON given now also flows through `JsonData[A]`.
   Schema/codec forwarders stay top-level in `package orca` but are
   invisible from the user's side unless nested `derives JsonData`
   macro expansion needs them.
4. **Add free-form text companions on `LlmTool`**: `ask` for one-shot
   prompts, `startSession(prompt) → (id, reply)` and
   `continueSession(id, prompt) → reply` for multi-turn text flows. The
   structured `resultAs[O]` path remains for when responses should parse
   into a specific type.

The canonical import becomes:

```scala
import orca.{*, given}
```

## Why

Five Scala-3-specific constraints pin this shape:

- **Package/method identifier clash.** `orca` was both a package and a
  method; inside `import orca.*` the package always won for dotted
  lookups, so `orca.JsonData` wouldn't resolve. Renaming the method
  (`flow`) removes the clash without displacing the package name users
  already type in `//> using dep com.virtuslab::orca`.
- **Fewer-braces plus context functions.** Scala 3 propagates the
  `FlowContext ?=>` given from a context-function parameter into a
  `f: <block>` body *only* when `f` has exactly one parameter list with
  exactly one parameter. Overloads, two lists, or a body after
  defaulted positional params silently drop the given. Hence `flow`
  takes only the body and `flowWith(...)` is a separate method for
  configuration.
- **Sibling backend packages keep colliding with accessors.**
  `orca.claude` (a package) shadowed `claude` (an accessor); moving the
  impls to `orca.tools.claude` eliminates the shadow and gives us a
  home for `orca.tools.codex` by symmetry. The flat OS-backed tools
  moved under `orca.tools.fs` / `git` / `github` so `orca.tools` is a
  namespace, not a half-namespace-half-leaf.
- **Given imports are separate from wildcard imports.** Scala 3
  excludes givens from `import foo.*`. The `given` selector in
  `import orca.{*, given}` is required; it's the only way to bring the
  `schemaFromJsonData` / `codecFromJsonData` forwarders into the
  user's lexical scope. The forwarders themselves can't be eliminated
  because `derives JsonData` on a parent case class expands
  `Schema.derived[Parent]` at the user's compile site, which recurses
  into `Schema[Child]`, which lives — via the forwarder — on the
  child's `given JsonData[Child]`.
- **One typeclass instead of two.** Bounding `result[O]` on
  `[O: Schema: ConfiguredJsonValueCodec]` leaked two implementation
  typeclasses into every user-visible signature. `[O: JsonData]` keeps
  the user surface to one name, and `JsonData` is the only thing a case
  class has to `derive`.

## Consequences

- A user's minimum ceremony is:

  ```scala
  //> using dep "com.virtuslab::orca:0.1.0-SNAPSHOT"
  import orca.{*, given}
  case class Plan(...) derives JsonData
  flow:
    claude.resultAs[Plan].autonomous(userPrompt)
  ```

  No other imports, no Schema/codec typeclasses in scope, no qualified
  names.

- `flow:` and `flowWith(...): body` cannot be unified behind one name
  without reintroducing the fewer-braces regression. They stay as two
  named entry points; scaladoc on each explains the split.

- Backend authors place their code under `orca.tools.<backend>` and
  follow the same pattern as `orca.tools.claude`. A hypothetical
  `orca.tools.codex` needs no changes in user code — the `codex`
  accessor already resolves against `FlowContext.codex`.

- A compile-time canary at
  `/workspaces/orca-sandbox/runner/src/test/scala/flowtests/FlowCompilesTest.scala`
  lives outside `package orca.*` and exercises every DSL accessor from
  a third-party-script perspective. Any regression in what
  `import orca.{*, given}` brings in fails this file's compilation,
  which fails `sbt test` — closing the gap that let every pre-refactor
  DSL issue reach the README untested.

## Alternatives considered

- **Keep `orca:` as the entry.** Needs either collapsing to a single
  list (breaks named-arg overrides) or a trailing-body-after-defaults
  shape (still loses the given). Neither preserves bare `orca:` plus
  `orca(git = ...)`.
- **Drop the forwarder givens and make `derives JsonData` emit
  `given Schema[A]` and `given codec[A]` alongside.** `derives`
  machinery only emits one given per derivation; the alternative is
  requiring users to write `derives JsonData, Schema,
  ConfiguredJsonValueCodec` on every case class. Worse UX than the
  single extra `given` selector on the import.
- **Put backends in `orca.backends.<name>`.** Works, but splits the
  impl tree into two roots (`orca.tools.*` for tool impls,
  `orca.backends.*` for LLM backends). Users would have to remember the
  distinction; `orca.tools.<capability>` gives one answer to "where's
  the impl of tool X".
