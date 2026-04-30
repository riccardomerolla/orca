package orca

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.{
  CodecMakerConfig,
  JsonCodecMaker
}
import sttp.tapir.Schema

enum Severity:
  case Critical
  case Warning
  case Info

object Severity:
  // Severity keeps its own Schema + JsonValueCodec so the enum renders as a
  // plain JSON string ("Critical", "Warning", "Info") in prompts and output
  // rather than the default object-with-discriminator shape.
  given Schema[Severity] = Schema.derivedEnumeration.defaultStringBased
  given JsonValueCodec[Severity] =
    JsonCodecMaker.make(CodecMakerConfig.withDiscriminatorFieldName(None))

/** A single review finding. `shortSummary` is the one-line user-facing
  * label (rendered in the event log under `▶`); `description` is the
  * longer form fed back to the fixing agent and includes whatever
  * context the reviewer gave. The split mirrors `Plan.Task`'s
  * shortSummary/description pair so flow scripts that handle issues
  * and tasks can use the same field names.
  */
case class ReviewIssue(
    severity: Severity,
    confidence: Double,
    shortSummary: String,
    description: String,
    file: Option[String],
    line: Option[Int],
    suggestion: Option[String]
) derives JsonData

case class ReviewResult(
    issues: List[ReviewIssue],
    summary: String
) derives JsonData

object ReviewResult:
  val empty: ReviewResult = ReviewResult(Nil, "")

  /** Per-reviewer announce: silent for clean reviews, otherwise a
    * one-line "found N issues" summary with each issue's shortSummary
    * as a bullet. This is what shows up under `▶` after each reviewer
    * runs in `gatherReviews` — the alternative would be the raw JSON
    * the agent produced, which reads as noise.
    */
  given Announce[ReviewResult] = Announce.from: r =>
    if r.issues.isEmpty then ""
    else
      val bullets = r.issues.map(i => s"  - [${i.severity}] ${i.shortSummary}").mkString("\n")
      val plural = if r.issues.size == 1 then "" else "s"
      s"Found ${r.issues.size} issue$plural:\n$bullets"

case class IgnoredIssue(issue: ReviewIssue, reason: String) derives JsonData

case class IgnoredIssues(issues: List[IgnoredIssue]) derives JsonData:
  def ++(other: IgnoredIssues): IgnoredIssues = IgnoredIssues(
    issues ++ other.issues
  )
  def nonEmpty: Boolean = issues.nonEmpty
  def format: String =
    issues
      .map(i => s"- [${i.issue.severity}] ${i.issue.shortSummary}: ${i.reason}")
      .mkString("\n")

object IgnoredIssues:
  /** Per-fix-call announce: silent when nothing was ignored, otherwise
    * a "marked N as won't-fix" line so the user can see at a glance
    * what the fixing agent decided to set aside. Empty `issues` is the
    * "agent attempted to fix everything" case — still surfaced via
    * the iteration's "Fixed/Unable to fix" closing step.
    */
  given Announce[IgnoredIssues] = Announce.from: ii =>
    if ii.issues.isEmpty then ""
    else
      val plural = if ii.issues.size == 1 then "" else "s"
      val bullets = ii.issues.map: i =>
        s"  - [${i.issue.severity}] ${i.issue.shortSummary} — ${i.reason}"
      .mkString("\n")
      s"Marked ${ii.issues.size} review comment$plural as won't-fix:\n$bullets"

case class ReviewContext(summary: String, filesChanged: List[String])
    derives JsonData

case class SelectedReviewers(names: List[String]) derives JsonData:
  def pick(all: List[LlmTool[?]]): List[LlmTool[?]] =
    all.filter(r => names.contains(r.name))
