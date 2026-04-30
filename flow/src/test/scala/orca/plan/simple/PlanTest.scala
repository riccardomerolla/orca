package orca.plan.simple

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}
import orca.JsonData

class PlanTest extends munit.FunSuite:

  /** The library's structured-call surface bounds on `JsonData[O]`,
    * which derives the codec we need; this test pins the round-trip
    * so a `derives JsonData` regression is caught early.
    */
  test("Plan round-trips through JSON via the JsonData codec"):
    val plan = Plan(
      tasks = List(
        Task(
          branchName = "add-multiply",
          shortSummary = "Add multiply",
          description = "Add a multiply(int a, int b) method to Calculator."
        ),
        Task(
          branchName = "add-divide",
          shortSummary = "Add divide",
          description = "Add a divide(int, int) method with a zero-divisor guard."
        )
      )
    )
    given codec: com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec[Plan] =
      summon[JsonData[Plan]].codec
    val json = writeToString(plan)
    val parsed = readFromString[Plan](json)
    assertEquals(parsed, plan)

  test("Announce[Plan] produces a header + per-task bullet summary"):
    val plan = Plan(List(
      Task("feat-a", "Add feature A", "do A"),
      Task("feat-b", "Add feature B", "do B")
    ))
    val msg = summon[orca.Announce[Plan]].message(plan)
    assert(
      msg.startsWith("Planned 2 tasks on branch 'feat-a'"),
      s"expected the header; got: $msg"
    )
    assert(msg.contains("- Add feature A"), s"missing task A; got: $msg")
    assert(msg.contains("- Add feature B"), s"missing task B; got: $msg")

  test("Announce[Plan] returns empty for an empty plan (no Step emitted)"):
    assertEquals(summon[orca.Announce[Plan]].message(Plan(Nil)), "")
