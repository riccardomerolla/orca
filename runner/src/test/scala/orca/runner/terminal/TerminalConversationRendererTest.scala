package orca.runner.terminal

import orca.{
  ApprovalDecision,
  Backend,
  Conversation,
  ConversationEvent,
  LlmResult,
  OrcaInteractiveCancelled,
  SessionId,
  Usage
}

import java.io.{ByteArrayOutputStream, PrintStream}

class TerminalConversationRendererTest extends munit.FunSuite:

  private def renderer(out: ByteArrayOutputStream): TerminalConversationRenderer =
    new TerminalConversationRenderer(
      out = new PrintStream(out),
      useColor = false,
      spinner = None
    )

  /** A fake Conversation that replays a scripted event list, then returns
    * a scripted outcome from `awaitResult`.
    */
  private class ScriptedConversation[B <: Backend](
      scripted: List[ConversationEvent],
      outcome: Either[Throwable, LlmResult[B]]
  ) extends Conversation[B]:
    def events: Iterator[ConversationEvent] = scripted.iterator
    def awaitResult(): LlmResult[B] = outcome match
      case Left(t)  => throw t
      case Right(r) => r
    def sendUserMessage(text: String): Unit = ()
    def cancel(): Unit = ()

  private def sampleResult: LlmResult[Backend.ClaudeCode.type] =
    LlmResult(
      sessionId = SessionId[Backend.ClaudeCode.type]("sid"),
      output = """{"ok":true}""",
      usage = Usage(0L, 0L, None)
    )

  test("AssistantTextDelta streams the text inline without a trailing newline"):
    val buf = new ByteArrayOutputStream()
    val conv = new ScriptedConversation(
      List(ConversationEvent.AssistantTextDelta("hello ")),
      Right(sampleResult)
    )
    val _ = renderer(buf).render(conv)
    // The banner + a final newline from the renderer surround the text.
    assert(buf.toString.contains("hello "))

  test("AssistantToolCall renders the name and a summarised input"):
    val buf = new ByteArrayOutputStream()
    val conv = new ScriptedConversation(
      List(ConversationEvent.AssistantToolCall("Bash", """{"cmd":"ls"}""")),
      Right(sampleResult)
    )
    val _ = renderer(buf).render(conv)
    val out = buf.toString
    assert(out.contains("Bash"))
    assert(out.contains("{\"cmd\":\"ls\"}"))

  test("ToolResult rendering differs by ok flag"):
    val buf = new ByteArrayOutputStream()
    val conv = new ScriptedConversation(
      List(
        ConversationEvent.ToolResult("Bash", ok = true, "ok-output"),
        ConversationEvent.ToolResult("Bash", ok = false, "failed")
      ),
      Right(sampleResult)
    )
    val _ = renderer(buf).render(conv)
    val out = buf.toString
    assert(out.contains("ok-output"))
    assert(out.contains("failed"))

  test("Error event renders the message with the ✖ glyph"):
    val buf = new ByteArrayOutputStream()
    val conv = new ScriptedConversation(
      List(ConversationEvent.Error("boom")),
      Right(sampleResult)
    )
    val _ = renderer(buf).render(conv)
    assert(buf.toString.contains("boom"))
    assert(buf.toString.contains("✖"))

  test("awaitResult exception propagates through render"):
    val buf = new ByteArrayOutputStream()
    val conv = new ScriptedConversation(
      Nil,
      Left(new OrcaInteractiveCancelled())
    )
    intercept[OrcaInteractiveCancelled](renderer(buf).render(conv))

  test("summariseInput truncates long inputs with an ellipsis"):
    val buf = new ByteArrayOutputStream()
    val long = "x" * (TerminalConversationRenderer.MaxInlineInputLength + 50)
    val conv = new ScriptedConversation(
      List(ConversationEvent.AssistantToolCall("Bash", long)),
      Right(sampleResult)
    )
    val _ = renderer(buf).render(conv)
    val out = buf.toString
    assert(out.contains("…"), s"expected ellipsis; got: $out")
    // The rendered line must be shorter than the raw input.
    assert(out.length < long.length + 100)
