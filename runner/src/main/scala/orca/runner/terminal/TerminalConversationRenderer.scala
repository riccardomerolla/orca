package orca.runner.terminal

import orca.{
  ApprovalDecision,
  Backend,
  Conversation,
  ConversationEvent,
  LlmResult
}
import org.jline.reader.{LineReaderBuilder, UserInterruptException}
import org.jline.terminal.TerminalBuilder

import java.io.PrintStream

/** Renders a [[Conversation]] to the terminal: stream agent text/thinking
  * as it arrives, announce tool calls and results, and prompt the user
  * for approval decisions and free-form input. Designed to be single-use
  * per conversation; construct, call [[render]], then the underlying
  * JLine terminal is closed on the way out.
  *
  * The renderer intentionally does no global signal handling — a Ctrl-C
  * hit while streaming still kills the JVM today. Approval prompts,
  * which is where users most often want a graceful escape, handle
  * `UserInterruptException` from JLine and call `conversation.cancel()`
  * so the enclosing `flow` can recover.
  */
private[terminal] class TerminalConversationRenderer(
    out: PrintStream,
    useColor: Boolean,
    spinner: Option[OrcaSpinner],
    showThinking: Boolean = true
):

  import TerminalConversationRenderer.*

  private val terminal =
    TerminalBuilder.builder().system(true).dumb(true).build()
  private val lineReader =
    LineReaderBuilder.builder().terminal(terminal).build()

  def render[B <: Backend](conversation: Conversation[B]): LlmResult[B] =
    spinner.foreach(_.stop())
    out.println(paint(fansi.Color.Yellow, "[interactive session]"))
    try
      conversation.events.foreach(evt => handle(evt, conversation))
      conversation.awaitResult()
    finally
      try terminal.close()
      catch case _: Throwable => ()

  private def handle[B <: Backend](
      event: ConversationEvent,
      conversation: Conversation[B]
  ): Unit = event match
    case ConversationEvent.AssistantTextDelta(text)     => renderText(text)
    case ConversationEvent.AssistantThinkingDelta(text) => renderThinking(text)
    case ConversationEvent.AssistantToolCall(name, input) =>
      renderToolCall(name, input)
    case ConversationEvent.ToolResult(_, ok, content) =>
      renderToolResult(ok, content)
    case ConversationEvent.AssistantTurnEnd => renderTurnEnd()
    case ConversationEvent.Error(message)   => renderError(message)
    case ConversationEvent.ApproveTool(name, input, respond) =>
      promptApproval(name, input, respond, conversation)

  // --- Rendering ---

  private def renderText(text: String): Unit =
    spinner.foreach(_.stop())
    out.print(paint(fansi.Color.Reset, text))
    out.flush()

  private def renderThinking(text: String): Unit =
    if showThinking then
      spinner.foreach(_.stop())
      out.print(paint(fansi.Color.DarkGray, text))
      out.flush()

  private def renderToolCall(name: String, input: String): Unit =
    spinner.foreach(_.stop())
    out.println()
    out.println(
      paint(fansi.Color.DarkGray, s"  → $name${summariseInput(input)}")
    )

  private def renderToolResult(ok: Boolean, content: String): Unit =
    spinner.foreach(_.stop())
    val colour = if ok then fansi.Color.DarkGray else fansi.Color.Red
    val label = if ok then "←" else "✖"
    out.println(paint(colour, s"  $label ${summariseContent(content)}"))

  private def renderTurnEnd(): Unit =
    out.println()
    // Agent may continue (tool roundtrip, follow-up turn) — restart the
    // spinner to signal "working"; the first content event will stop it.
    spinner.foreach(_.start("thinking"))

  private def renderError(message: String): Unit =
    spinner.foreach(_.stop())
    out.println(paint(fansi.Color.Red, s"  ✖ $message"))

  // --- Prompts ---

  private def promptApproval[B <: Backend](
      toolName: String,
      rawInput: String,
      respond: ApprovalDecision => Unit,
      conversation: Conversation[B]
  ): Unit =
    spinner.foreach(_.stop())
    out.println()
    out.println(
      paint(fansi.Color.Yellow, s"? $toolName requested: ${summariseInput(rawInput)}")
    )
    val prompt = paint(fansi.Color.Yellow, "  [y]es / [n]o ? ")
    try
      val answer = lineReader.readLine(prompt).trim.toLowerCase
      if answer.startsWith("y") then respond(ApprovalDecision.Allow())
      else
        respond(
          ApprovalDecision.Deny(Some(s"user denied via terminal (answered '$answer')"))
        )
    catch case _: UserInterruptException => conversation.cancel()

  // --- Helpers ---

  private def summariseInput(rawInput: String): String =
    val trimmed = rawInput.replaceAll("\\s+", " ").trim
    if trimmed.length <= MaxInlineInputLength then s": $trimmed"
    else s": ${trimmed.take(MaxInlineInputLength)}…"

  private def summariseContent(raw: String): String =
    val trimmed = raw.replaceAll("\\s+", " ").trim
    if trimmed.length <= MaxInlineContentLength then trimmed
    else s"${trimmed.take(MaxInlineContentLength)}…"

  private def paint(attr: fansi.EscapeAttr, text: String): String =
    if useColor then attr(text).render else text

private[terminal] object TerminalConversationRenderer:
  val MaxInlineInputLength: Int = 120
  val MaxInlineContentLength: Int = 160
