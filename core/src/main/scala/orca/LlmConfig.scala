package orca

import ox.scheduling.Schedule

import scala.concurrent.duration.*

case class LlmConfig(
    model: Option[String] = None,
    systemPrompt: Option[String] = None,
    autoApprove: AutoApprove = AutoApprove.All,
    onUnapproved: UnapprovedPolicy = UnapprovedPolicy.Deny,
    retrySchedule: Schedule = LlmConfig.defaultRetrySchedule
)

object LlmConfig:
  val default: LlmConfig = LlmConfig()
  val defaultRetrySchedule: Schedule =
    Schedule.exponentialBackoff(1.second).maxRepeats(3)

enum AutoApprove derives CanEqual:
  case All
  case Only(tools: Set[String])

enum UnapprovedPolicy derives CanEqual:
  case Deny
  case AskUser
