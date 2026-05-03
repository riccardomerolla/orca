package orca

enum OrcaEvent:
  case StageStarted(name: String)
  case StageCompleted(name: String, result: String)
  case ToolUse(tool: String, args: String)

  /** A single instantaneous note in the event log — neither a stage (no
    * completion) nor a stream-of-text (no continuation). Tools emit these for
    * discrete progress: "switched to branch X", "discarded N issues", etc.
    */
  case Step(message: String)

  /** Token usage for a single LLM call, attributed along two independent axes:
    *
    *   - `agent` is the [[LlmTool.name]] that issued the call. For reviewer
    *     agents this carries the reviewer identity (`abstraction`,
    *     `performance`, …); for the main coding agent it's `claude` / `codex`
    *     (or whatever the script renamed it to via `withName`).
    *   - `model` is the concrete model the backend reports it actually served
    *     the call with. `None` when the response didn't carry it and no model
    *     was pinned via `LlmConfig.model`.
    *
    * `CostTracker` summarises usage along both axes — by-agent shows where the
    * tokens were spent, by-model shows which models cost what.
    */
  case TokensUsed(agent: String, model: Option[String], usage: Usage)

  /** The agent's final structured payload, after parsing succeeded. `raw` is
    * the verbatim text the agent produced (typically JSON); `summary` is the
    * `Announce[O]`-derived human-readable form when an instance is configured.
    */
  case StructuredResult(raw: String, summary: Option[String])
  case Error(message: String)

trait OrcaListener:
  def onEvent(event: OrcaEvent): Unit

object OrcaListener:
  /** Drops every event. The default for tools that may run without a wired-up
    * dispatcher (unit tests, lightweight scripts).
    */
  val noop: OrcaListener = (_: OrcaEvent) => ()
