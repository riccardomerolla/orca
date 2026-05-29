package orca.plan

import orca.events.EventDispatcher

/** Runtime wiring of the autonomous planning grid: each operation pairs its
  * result with the producing session, and `triage` converts the wire
  * [[BugTriage]] into a [[Triage]]. The conversions themselves are covered by
  * [[AssessThenPlanTest]] (toVerdict) and [[BugTriageTest]] (toTriage); the
  * interactive cells share the same helper and are pinned at compile time by
  * `flowtests.FlowCompilesTest`.
  */
class PlanGridTest extends munit.FunSuite:

  private given orca.FlowContext =
    new orca.TestFlowContext(new EventDispatcher(Nil))

  private val samplePlan = Plan(
    epicId = "x",
    description = "d",
    tasks = List(Task(Title("t1"), "body"))
  )

  test("autonomous.from pairs the plan with the producing session"):
    val result = Plan.autonomous.from("prompt", new CannedResultLlm(samplePlan))
    assertEquals(result.sessionId.value, "stub-sid")
    assertEquals(result.value, samplePlan)

  test("autonomous.triage converts the wire BugTriage into a Triage"):
    val wire = BugTriage(
      isBug = true,
      notBugExplanation = "",
      canTest = true,
      reproductionSteps = "",
      failingTestPath = Some("src/test/scala/FooTest.scala"),
      branchName = "fix-foo",
      summary = "Foo overflows"
    )
    val result = Plan.autonomous.triage("report", new CannedResultLlm(wire))
    assertEquals(result.sessionId.value, "stub-sid")
    assertEquals(
      result.value,
      Triage.Testable(
        "Foo overflows",
        "fix-foo",
        "src/test/scala/FooTest.scala"
      )
    )
