package orca

import com.github.plokhotnyuk.jsoniter_scala.core.{
  readFromString,
  writeToString
}

class ReviewTypesTest extends munit.FunSuite:
  test("ReviewResult round-trips through JSON"):
    val original = ReviewResult(
      issues = List(
        ReviewIssue(
          severity = Severity.Critical,
          confidence = 0.95,
          description = "null pointer risk",
          file = Some("Foo.scala"),
          line = Some(42),
          suggestion = Some("add a null check")
        ),
        ReviewIssue(
          severity = Severity.Info,
          confidence = 0.4,
          description = "stylistic nitpick",
          file = None,
          line = None,
          suggestion = None
        )
      ),
      summary = "one critical, one info"
    )
    val json = writeToString(original)
    val parsed = readFromString[ReviewResult](json)
    assertEquals(parsed, original)

  test("IgnoredIssues formats and combines"):
    val issue = ReviewIssue(Severity.Warning, 0.5, "style", None, None, None)
    val a = IgnoredIssues(List(IgnoredIssue(issue, "accepted as-is")))
    val b = IgnoredIssues(List(IgnoredIssue(issue, "deferred")))
    val combined = a ++ b
    assertEquals(combined.issues.size, 2)
    assert(combined.nonEmpty)
    assert(combined.format.contains("Warning"))
