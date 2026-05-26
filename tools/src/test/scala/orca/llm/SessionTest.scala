package orca.llm

import java.util.concurrent.atomic.AtomicReference

private enum RecordedCall:
  case Start(prompt: String)
  case Continue(sessionId: String, prompt: String)

/** Records every autonomous call so `SessionTest` can assert which session id
  * was used and whether start vs. continue was picked. `resultAs` /
  * `withConfig` / friends throw — they're not in scope for `Session`.
  */
private class RecordingTool extends LlmTool[BackendTag.ClaudeCode.type]:
  val calls = new AtomicReference[List[RecordedCall]](Nil)
  val name: String = "rec"

  val autonomous: AutonomousTextCall[BackendTag.ClaudeCode.type] =
    new AutonomousTextCall[BackendTag.ClaudeCode.type]:
      def run(prompt: String, config: LlmConfig = LlmConfig.default): String = ???
      def startSession(
          prompt: String,
          config: LlmConfig = LlmConfig.default
      ): (SessionId[BackendTag.ClaudeCode.type], String) =
        val _ = calls.updateAndGet(RecordedCall.Start(prompt) :: _)
        (SessionId[BackendTag.ClaudeCode.type]("rec-sid"), "")
      def continueSession(
          sessionId: SessionId[BackendTag.ClaudeCode.type],
          prompt: String,
          config: LlmConfig = LlmConfig.default
      ): String =
        val _ = calls.updateAndGet(
          RecordedCall.Continue(SessionId.value(sessionId), prompt) :: _
        )
        ""

  def resultAs[O: JsonData: Announce]: LlmCall[BackendTag.ClaudeCode.type, O] = ???
  def withConfig(c: LlmConfig) = this
  def withSystemPrompt(p: String) = this
  def withName(n: String) = this
  def withReadOnly = this

class SessionTest extends munit.FunSuite:

  test("first run starts a session; subsequent runs continue the same session"):
    val tool = new RecordingTool
    val s = tool.session
    val sid1 = s.run("first")
    val sid2 = s.run("second")
    val sid3 = s.run("third")
    assertEquals(SessionId.value(sid1), "rec-sid")
    assertEquals(sid2, sid1)
    assertEquals(sid3, sid1)
    assertEquals(
      tool.calls.get().reverse,
      List(
        RecordedCall.Start("first"),
        RecordedCall.Continue("rec-sid", "second"),
        RecordedCall.Continue("rec-sid", "third")
      )
    )

  test("id is None before the first run, Some after"):
    val tool = new RecordingTool
    val s = tool.session
    assertEquals(s.id, None)
    val _ = s.run("kick off")
    assertEquals(s.id.map(SessionId.value), Some("rec-sid"))

  test("tool.session returns a fresh Session each time"):
    // Two sessions against the same tool are independent — both start fresh.
    val tool = new RecordingTool
    val a = tool.session
    val b = tool.session
    val _ = a.run("a-1")
    val _ = b.run("b-1")
    val starts = tool.calls.get().collect:
      case RecordedCall.Start(p) => p
    assertEquals(starts.toSet, Set("a-1", "b-1"))
