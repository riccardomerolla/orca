package orca.runner.terminal

import orca.{
  ApprovalDecision,
  Backend,
  Conversation,
  ConversationEvent,
  LlmResult
}
import org.jline.reader.{LineReader, LineReaderBuilder, UserInterruptException}
import org.jline.terminal.TerminalBuilder

import java.io.PrintStream
import scala.util.control.NonFatal

/** Renders a [[Conversation]] to the terminal. The layout aims for a
  * Claude-Code-like aesthetic: the user's opening prompt sits on its
  * own section, the agent's prose streams as plain text, and each tool
  * call/result gets a compact one-line summary tagged with a glyph.
  *
  * Spacing is controlled by a tiny section state machine — consecutive
  * tool events don't grow blank lines between them, but a transition
  * from prose to a tool block (or back) gets exactly one separator.
  * This keeps tool-heavy turns tight while leaving prose visually
  * detached from mechanical actions.
  *
  * The renderer is single-use per conversation; the JLine terminal is
  * closed on the way out. No global signal handling — Ctrl-C during
  * streaming still kills the JVM today. Approval prompts catch
  * `UserInterruptException` and cancel the conversation instead.
  */
private[terminal] class TerminalConversationRenderer(
    out: PrintStream,
    useColor: Boolean,
    spinner: Option[OrcaSpinner],
    showThinking: Boolean = false,
    prompter: TerminalConversationRenderer.Prompter =
      TerminalConversationRenderer.JLinePrompter
):

  import TerminalConversationRenderer.*

  /** Section + midText together form the renderer's only state: they
    * drive spacing decisions between events. Held in a single var so
    * the invariant (`midText => section == Prose`) stays visible and
    * the two fields can't drift apart.
    */
  private var state: RenderState = RenderState.empty

  /** Explicit Either→exception boundary. The flow's stage machinery
    * propagates failures via exceptions, so a user cancel is rethrown
    * as [[OrcaInteractiveCancelled]] for the enclosing `stage(...)` to
    * handle. The conversation API stays honest (Either) at its layer;
    * the renderer is the converter.
    */
  def render[B <: Backend](conversation: Conversation[B]): LlmResult[B] =
    try
      conversation.events.foreach(dispatch(_, conversation))
      conversation.awaitResult() match
        case Right(result)    => result
        case Left(cancelled) => throw cancelled
    finally closePrompter()

  private def dispatch[B <: Backend](
      event: ConversationEvent,
      conversation: Conversation[B]
  ): Unit = event match
    case ConversationEvent.UserMessage(text)            => renderUserMessage(text)
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

  private def renderUserMessage(text: String): Unit =
    stopSpinner()
    beginSection(Section.Prose)
    out.println(paint(UserHeaderStyle, s"$UserGlyph you"))
    out.println(paint(UserBodyStyle, indent(text)))
    // Explicit blank after the user block so the first agent event
    // lands detached; reset the section so the next `beginSection`
    // doesn't insert a second one.
    out.println()
    state = RenderState.empty
    out.flush()
    spinner.foreach(_.start("thinking"))

  private def renderText(text: String): Unit =
    streamInProse(text, AssistantGlyph, AssistantGlyphStyle, AssistantTextStyle)

  private def renderThinking(text: String): Unit =
    if showThinking then
      streamInProse(text, ThinkingGlyph, ThinkingStyle, ThinkingStyle)

  private def streamInProse(
      text: String,
      glyph: String,
      glyphStyle: fansi.Attrs,
      textStyle: fansi.Attrs
  ): Unit =
    stopSpinner()
    if !state.midText then
      beginSection(Section.Prose)
      out.print(paint(glyphStyle, s"$glyph "))
      state = state.copy(midText = true)
    out.print(paint(textStyle, text))
    out.flush()

  private def renderToolCall(name: String, input: String): Unit =
    stopSpinner()
    beginSection(Section.Tool)
    val args = ToolInputSummary.summarise(input, MaxInlineInputLength)
    val head = paint(ToolNameStyle, s"$ToolCallGlyph $name")
    val tail = if args.isEmpty then "" else " " + paint(ToolArgsStyle, args)
    out.println(head + tail)

  private def renderToolResult(ok: Boolean, content: String): Unit =
    stopSpinner()
    beginSection(Section.Tool)
    val glyph = if ok then ToolResultGlyph else ToolErrorGlyph
    val style = if ok then ToolResultStyle else ErrorStyle
    out.println(paint(style, s"  $glyph ${truncate(content, MaxInlineContentLength)}"))

  private def renderTurnEnd(): Unit =
    closeTextRun()
    spinner.foreach(_.start("thinking"))

  private def renderError(message: String): Unit =
    stopSpinner()
    beginSection(Section.Prose)
    out.println(paint(ErrorStyle, s"$ErrorGlyph $message"))

  /** Close any pending streaming text and insert a single blank line
    * when transitioning between different section kinds; tight inside
    * the same section. Every "discrete" renderer calls this first.
    */
  private def beginSection(next: Section): Unit =
    closeTextRun()
    if state.section != Section.None && state.section != next then out.println()
    state = RenderState(next, midText = false)

  private def closeTextRun(): Unit =
    if state.midText then
      out.println()
      state = state.copy(midText = false)

  // --- Prompts ---

  private def promptApproval[B <: Backend](
      toolName: String,
      rawInput: String,
      respond: ApprovalDecision => Unit,
      conversation: Conversation[B]
  ): Unit =
    stopSpinner()
    beginSection(Section.Prose)
    val summary = ToolInputSummary.summarise(rawInput, MaxInlineInputLength)
    out.println(paint(ApprovalStyle, s"$ApprovalGlyph $toolName requested: $summary"))
    prompter.ask(paint(ApprovalStyle, "  [y]es / [n]o ? ")) match
      case PromptOutcome.Answer(reply) => respond(decisionFor(reply))
      case PromptOutcome.Interrupted   => conversation.cancel()

  private def decisionFor(reply: String): ApprovalDecision =
    val normalised = reply.trim.toLowerCase
    if normalised.startsWith("y") then ApprovalDecision.Allow()
    else ApprovalDecision.Deny(
      Some(s"user denied via terminal (answered '$normalised')")
    )

  // --- Helpers ---

  private def stopSpinner(): Unit = spinner.foreach(_.stop())

  private def closePrompter(): Unit =
    try prompter.close()
    catch case NonFatal(_) => ()

  private def indent(text: String): String =
    text.linesIterator.map(line => s"  $line").mkString("\n")

  private def truncate(raw: String, maxLength: Int): String =
    val collapsed = raw.replaceAll("\\s+", " ").trim
    if collapsed.length <= maxLength then collapsed
    else s"${collapsed.take(maxLength)}…"

  private def paint(attr: fansi.Attrs, text: String): String =
    if useColor then attr(text).render else text

private[terminal] object TerminalConversationRenderer:
  val MaxInlineInputLength: Int = 120
  // Tool results are large file reads or command output; show just
  // enough for "something happened" without wrapping past one line.
  val MaxInlineContentLength: Int = 100

  val UserGlyph: String       = "▸"
  val AssistantGlyph: String  = "●"
  val ThinkingGlyph: String   = "·"
  val ToolCallGlyph: String   = "⏺"
  val ToolResultGlyph: String = "⎿"
  val ToolErrorGlyph: String  = "✖"
  val ErrorGlyph: String      = "✖"
  val ApprovalGlyph: String   = "?"

  val UserHeaderStyle: fansi.Attrs     = fansi.Color.Cyan ++ fansi.Bold.On
  val UserBodyStyle: fansi.Attrs       = fansi.Color.Cyan
  val AssistantGlyphStyle: fansi.Attrs = fansi.Color.Magenta ++ fansi.Bold.On
  val AssistantTextStyle: fansi.Attrs  = fansi.Attrs.Empty
  val ThinkingStyle: fansi.Attrs       = fansi.Color.DarkGray
  val ToolNameStyle: fansi.Attrs       = fansi.Color.Blue ++ fansi.Bold.On
  val ToolArgsStyle: fansi.Attrs       = fansi.Color.DarkGray
  val ToolResultStyle: fansi.Attrs     = fansi.Color.DarkGray
  val ErrorStyle: fansi.Attrs          = fansi.Color.Red
  val ApprovalStyle: fansi.Attrs       = fansi.Color.Yellow

  private[terminal] enum Section:
    case None, Prose, Tool

  /** `midText` is only meaningful inside a Prose section — the
    * constructor keeps that invariant loose so tests can build edge
    * cases, but [[TerminalConversationRenderer.beginSection]] always
    * normalises it when opening a non-Prose section.
    */
  private[terminal] case class RenderState(section: Section, midText: Boolean)

  private[terminal] object RenderState:
    val empty: RenderState = RenderState(Section.None, midText = false)

  /** Outcome of a readline-style prompt. */
  enum PromptOutcome:
    case Answer(reply: String)
    case Interrupted

  /** Seam for the approval prompt. Tests inject a stub so they can
    * assert prompt text and feed scripted replies; production uses the
    * JLine-backed implementation below.
    */
  trait Prompter:
    def ask(prompt: String): PromptOutcome
    def close(): Unit

  /** Default production prompter: JLine line reader. Lazy so the
    * terminal is only opened when an approval prompt actually fires —
    * pure non-interactive sessions never allocate a terminal.
    */
  object JLinePrompter extends Prompter:
    private lazy val terminal =
      TerminalBuilder.builder().system(true).dumb(true).build()
    private lazy val reader: LineReader =
      LineReaderBuilder.builder().terminal(terminal).build()

    def ask(prompt: String): PromptOutcome =
      try PromptOutcome.Answer(reader.readLine(prompt))
      catch case _: UserInterruptException => PromptOutcome.Interrupted

    def close(): Unit = terminal.close()
