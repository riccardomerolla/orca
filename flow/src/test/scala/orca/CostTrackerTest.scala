package orca

class CostTrackerTest extends munit.FunSuite:

  test("starts at zero and ignores non-TokensUsed events"):
    val tracker = new CostTracker
    tracker.onEvent(OrcaEvent.StageStarted("x"))
    tracker.onEvent(OrcaEvent.LlmOutput("hi"))
    assertEquals(tracker.total, Usage.empty)

  test("accumulates input and output tokens across multiple TokensUsed events"):
    val tracker = new CostTracker
    tracker.onEvent(
      OrcaEvent.TokensUsed(Usage(100L, 50L, Some(BigDecimal("0.01"))))
    )
    tracker.onEvent(
      OrcaEvent.TokensUsed(Usage(30L, 20L, Some(BigDecimal("0.005"))))
    )
    assertEquals(
      tracker.total,
      Usage(130L, 70L, Some(BigDecimal("0.015")))
    )

  test("cost stays defined even when some events omit the cost"):
    val tracker = new CostTracker
    tracker.onEvent(
      OrcaEvent.TokensUsed(Usage(10L, 5L, Some(BigDecimal("0.002"))))
    )
    tracker.onEvent(OrcaEvent.TokensUsed(Usage(1L, 1L, None)))
    assertEquals(tracker.total.cost, Some(BigDecimal("0.002")))

  test("cost is picked up when it first appears on a later event"):
    val tracker = new CostTracker
    tracker.onEvent(OrcaEvent.TokensUsed(Usage(10L, 5L, None)))
    tracker.onEvent(
      OrcaEvent.TokensUsed(Usage(1L, 1L, Some(BigDecimal("0.003"))))
    )
    assertEquals(tracker.total.cost, Some(BigDecimal("0.003")))

  test("cost is None only when every event omits cost"):
    val tracker = new CostTracker
    tracker.onEvent(OrcaEvent.TokensUsed(Usage(10L, 5L, None)))
    tracker.onEvent(OrcaEvent.TokensUsed(Usage(1L, 1L, None)))
    assertEquals(tracker.total.cost, None)

  test("summary includes token totals and cost when present"):
    val tracker = new CostTracker
    tracker.onEvent(
      OrcaEvent.TokensUsed(Usage(100L, 50L, Some(BigDecimal("0.0123"))))
    )
    val s = tracker.summary
    assert(s.contains("100 in"))
    assert(s.contains("50 out"))
    assert(s.contains("0.0123"))
