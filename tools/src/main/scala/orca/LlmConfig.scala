package orca

import ox.scheduling.Schedule

import scala.concurrent.duration.DurationInt

case class LlmConfig(
    model: Option[String] = None,
    systemPrompt: Option[String] = None,
    autoApprove: AutoApprove = AutoApprove.All,
    /** What to do when the agent wants a tool that isn't covered by
      * `autoApprove`. Only consulted in interactive stages where the user is
      * present to answer; in autonomous stages the call is denied outright.
      */
    onUnapproved: UnapprovedPolicy = UnapprovedPolicy.Deny,
    retrySchedule: Schedule = LlmConfig.defaultRetrySchedule
)

object LlmConfig:

  // IMPORTANT: declared BEFORE `default` — the case-class default for
  // `retrySchedule` reads `LlmConfig.defaultRetrySchedule`, and val
  // initialisation in an object runs in source order. If `default`
  // were first, its `retrySchedule` would latch onto a null value
  // that later callers would pass into ox's `retry` and blow up with
  // "Schedule.initialDelay() on null".
  val defaultRetrySchedule: Schedule =
    Schedule.exponentialBackoff(1.second).maxRepeats(3)

  /** The default LlmConfig. Implementations may use reference-equality with
    * this singleton as a cheap "caller omitted the arg" sentinel, so always
    * refer to `LlmConfig.default` rather than constructing a fresh one when you
    * want default-config semantics.
    */
  val default: LlmConfig = LlmConfig()

enum AutoApprove:
  case All
  case Only(tools: Set[String])

enum UnapprovedPolicy:
  case Deny
  case AskUser
