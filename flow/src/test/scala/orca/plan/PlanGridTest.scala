package orca.plan

import orca.events.EventDispatcher
import orca.llm.{BackendTag, SessionId}

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

  // --- post-planning steps (reviewed / briefed) on the planning session ---

  private def sessioned[A](value: A): Sessioned[BackendTag.ClaudeCode.type, A] =
    Sessioned(SessionId[BackendTag.ClaudeCode.type]("planner-sid"), value)

  test("reviewed on a bare plan returns the improved plan"):
    val improved = samplePlan.copy(description = "tighter")
    val result = sessioned(samplePlan).reviewed(new CannedResultLlm(improved))
    assertEquals(result.value, improved)

  test("briefed attaches the brief and threads the planning session"):
    val result = sessioned(samplePlan).briefed(new CannedTextLlm("the brief"))
    assertEquals(result.value, PlanWithBrief(samplePlan, "the brief"))
    assertEquals(result.sessionId.value, "planner-sid")

  test("reviewed on a PlanWithBrief reviews plan and brief together"):
    val improved =
      PlanWithBrief(samplePlan.copy(description = "tighter"), "sharper brief")
    val result =
      sessioned(PlanWithBrief(samplePlan, "brief"))
        .reviewed(new CannedResultLlm(improved))
    assertEquals(result.value, improved)

  test("briefed fails when the brief turn produces a blank brief"):
    val _ = intercept[orca.OrcaFlowException]:
      sessioned(samplePlan).briefed(new CannedTextLlm("   "))
