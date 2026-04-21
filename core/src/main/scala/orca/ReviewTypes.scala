package orca

import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import sttp.tapir.Schema

enum Severity derives Schema, ConfiguredJsonValueCodec:
  case Critical
  case Warning
  case Info

case class ReviewIssue(
    severity: Severity,
    confidence: Double,
    description: String,
    file: Option[String],
    line: Option[Int],
    suggestion: Option[String]
) derives Schema,
      ConfiguredJsonValueCodec

case class ReviewResult(
    issues: List[ReviewIssue],
    summary: String
) derives Schema,
      ConfiguredJsonValueCodec

object ReviewResult:
  val empty: ReviewResult = ReviewResult(Nil, "")

case class IgnoredIssue(issue: ReviewIssue, reason: String)
    derives Schema,
      ConfiguredJsonValueCodec

case class IgnoredIssues(issues: List[IgnoredIssue])
    derives Schema,
      ConfiguredJsonValueCodec:
  def ++(other: IgnoredIssues): IgnoredIssues = IgnoredIssues(
    issues ++ other.issues
  )
  def nonEmpty: Boolean = issues.nonEmpty
  def format: String =
    issues
      .map(i => s"- [${i.issue.severity}] ${i.issue.description}: ${i.reason}")
      .mkString("\n")

case class ReviewContext(summary: String, filesChanged: List[String])
    derives Schema,
      ConfiguredJsonValueCodec

case class SelectedReviewers(names: List[String])
    derives Schema,
      ConfiguredJsonValueCodec:
  def pick(all: List[LlmTool[?]]): List[LlmTool[?]] =
    all.filter(r => names.contains(r.name))
