package orca

class ConversationEventTest extends munit.FunSuite:

  test("ApproveTool round-trips its fields"):
    val evt = ConversationEvent.ApproveTool("req-1", "Bash", """{"cmd":"ls"}""")
    evt match
      case ConversationEvent.ApproveTool(requestId, toolName, rawInput) =>
        assertEquals(requestId, "req-1")
        assertEquals(toolName, "Bash")
        assertEquals(rawInput, """{"cmd":"ls"}""")
      case other => fail(s"expected ApproveTool, got $other")

  test("AssistantTextDelta and AssistantThinkingDelta are distinguishable"):
    val text = ConversationEvent.AssistantTextDelta("hello")
    val thinking = ConversationEvent.AssistantThinkingDelta("ponder")
    assertNotEquals[ConversationEvent, ConversationEvent](text, thinking)

  test("ApprovalDecision.Allow and Deny carry optional payloads"):
    assertEquals(
      ApprovalDecision.Allow(Some("""{"cmd":"safe"}""")).updatedInputJson,
      Some("""{"cmd":"safe"}""")
    )
    assertEquals(
      ApprovalDecision.Deny(Some("not allowed")).reason,
      Some("not allowed")
    )
    assertEquals(ApprovalDecision.Allow().updatedInputJson, None)
    assertEquals(ApprovalDecision.Deny().reason, None)

  test("OrcaInteractiveCancelled is an OrcaFlowException"):
    val cancelled = new OrcaInteractiveCancelled()
    assert(cancelled.isInstanceOf[OrcaFlowException])
    assertEquals(cancelled.getMessage, "interactive session cancelled")
