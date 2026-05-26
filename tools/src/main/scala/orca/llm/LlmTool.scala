package orca.llm

/** An LLM adapter usable from flow scripts — the handle you call from a
  * `flow(...)` block (`claude`, `codex`, etc.). Two paths to invoke the model:
  *
  *   - **`autonomous`** — free-form text, no structured output, no JSON schema
  *     wrapping. The agent's reply is returned verbatim.
  *   - **`resultAs[O]`** — fix the output type and obtain a call object that
  *     exposes both `autonomous` and `interactive` modes.
  *
  * Each mode has a single `run(input, resume = None, config = …)` method that
  * always returns `(SessionId[B], output)`. Pass `resume = Some(prevSid)` to
  * continue a prior session; `None` starts a fresh one. The session id is in
  * every return value so callers can decide whether to hold onto it for the
  * next call.
  *
  * The API never hides the autonomous-vs-interactive choice behind a default —
  * it's always visible at the call site as the leftmost segment after the tool
  * / call gateway.
  *
  * Parameterized by the concrete `BackendTag` so session ids and results carry
  * the backend identity at the type level.
  */
trait LlmTool[B <: BackendTag]:
  def name: String

  /** Free-form text autonomous calls. Use this when the agent's reply is prose
    * / code / anything that doesn't need to parse as a structured `O`. For
    * structured output (and the interactive-conversation path), use
    * [[resultAs]].
    */
  def autonomous: AutonomousTextCall[B]

  /** Fix the output type of a structured call and obtain a gateway with both
    * `autonomous` and `interactive` modes. `O` needs a `JsonData[O]` — `derives
    * JsonData` on a case class is the normal way to provide one.
    *
    * An `Announce[O]` is also required; the library's default given returns
    * `None` (no auto-announce), so callers don't need to do anything unless
    * they want a friendly summary on the channel. See [[Announce]].
    */
  def resultAs[O: JsonData: Announce]: LlmCall[B, O]

  def withConfig(config: LlmConfig): LlmTool[B]
  def withSystemPrompt(prompt: String): LlmTool[B]
  def withName(name: String): LlmTool[B]

  /** Return a sibling tool whose config has [[LlmConfig.readOnly]] flipped on
    * — claude maps this to `--permission-mode plan`, so Edit/Write/Bash are
    * unavailable to the agent. Preserves the rest of the tool's config
    * (model, system prompt, autoApprove). Used by planning helpers so
    * `claude.opus.withReadOnly` keeps the opus pin while gating writes.
    */
  def withReadOnly: LlmTool[B]

trait ClaudeTool extends LlmTool[BackendTag.ClaudeCode.type]:
  /** Pin the Claude model for subsequent calls, overriding `LlmConfig.model`.
    * Typical usage: `claude.haiku.autonomous.run("summarize this")._2` for a
    * cheap fast one-shot call (discard the returned session id).
    */
  def haiku: ClaudeTool
  def sonnet: ClaudeTool
  def opus: ClaudeTool

trait CodexTool extends LlmTool[BackendTag.Codex.type]:
  def mini: CodexTool

/** Free-form text autonomous calls — the `LlmTool.autonomous` shape. Single
  * method: `resume = None` starts a fresh session, `resume = Some(sid)`
  * continues that session. Always returns the (possibly-new) session id so
  * the caller can hold onto it for the next call.
  */
trait AutonomousTextCall[B <: BackendTag]:
  def run(
      prompt: String,
      resume: Option[SessionId[B]] = None,
      config: LlmConfig = LlmConfig.default
  ): (SessionId[B], String)
