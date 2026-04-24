package orca.runner.terminal

import orca.{
  Backend,
  Conversation,
  Interaction,
  LlmResult,
  OrcaEvent,
  OrcaListener
}

import java.io.PrintStream

/** Terminal-based `Interaction`. Renders stage transitions, tool uses,
  * streaming LLM output, and errors to a `PrintStream` (defaults to
  * stderr so the structured output on stdout stays clean).
  *
  * `useColor` and `animated` default to auto-detect: when the JVM has
  * no controlling console (e.g. the caller redirected stderr to a file
  * or we're running under a CI runner) we suppress both ANSI escapes
  * and the spinner animation. This prevents the spinner's rapid
  * cursor-up redraws from piling up as literal ANSI noise in logs.
  * `NO_COLOR`, `CI`, and `ORCA_NO_ANIMATION` all force both off.
  *
  * Unicode glyphs require a UTF-8 locale; on platforms with a non-UTF-8
  * default charset the caller should pass a PrintStream constructed
  * with `new PrintStream(out, true, "UTF-8")`.
  */
class TerminalInteraction(
    out: PrintStream = System.err,
    useColor: Boolean = TerminalInteraction.defaultUseColor,
    animated: Boolean = TerminalInteraction.defaultAnimated
) extends Interaction:

  private val listener = new TerminalListener
  private val listenersList: List[OrcaListener] = List(listener)
  private val spinner: Option[OrcaSpinner] =
    if animated then Some(new OrcaSpinner(out, useColor = useColor)) else None

  def listeners: List[OrcaListener] = listenersList

  /** Drive a live conversation to completion. Delegates to
    * [[TerminalConversationRenderer]] for the per-event rendering +
    * approval-prompt machinery; this class retains responsibility for
    * the ambient spinner and the `OrcaListener` surface.
    */
  def drive[B <: Backend](conversation: Conversation[B]): LlmResult[B] =
    new TerminalConversationRenderer(
      out = out,
      useColor = useColor,
      spinner = spinner
    ).render(conversation)

  private class TerminalListener extends OrcaListener:
    import TerminalInteraction.*
    def onEvent(event: OrcaEvent): Unit = event match
      case OrcaEvent.StageStarted(name) =>
        out.println(paint(fansi.Color.Cyan, s"$StageStartGlyph $name"))
        spinner.foreach(_.start(name))
      case OrcaEvent.StageCompleted(name, _) =>
        spinner.foreach(_.stop())
        out.println(paint(fansi.Color.Green, s"$StageDoneGlyph $name"))
      case OrcaEvent.LlmOutput(text) =>
        spinner.foreach(_.stop())
        out.print(text)
      case OrcaEvent.ToolUse(tool, args) =>
        spinner.foreach(_.stop())
        out.println(paint(fansi.Color.DarkGray, s"  → $tool: $args"))
      case OrcaEvent.TokensUsed(_, _) =>
        () // Token accounting is owned by CostTracker.
      case OrcaEvent.Error(message) =>
        spinner.foreach(_.stop())
        out.println(paint(fansi.Color.Red, s"$ErrorGlyph $message"))

  private def paint(attr: fansi.EscapeAttr, text: String): String =
    if useColor then attr(text).render else text

object TerminalInteraction:
  val StageStartGlyph: String = "▶"
  val StageDoneGlyph: String = "✔"
  val ErrorGlyph: String = "✖"

  /** ANSI colors default off when stderr isn't attached to a terminal
    * (no controlling console), the `NO_COLOR` convention is honoured,
    * or we detect a CI runner.
    */
  def defaultUseColor: Boolean =
    !sys.env.contains("NO_COLOR") && consolePresent && !ciDetected

  /** Animation is strictly a subset of colour — it additionally
    * writes cursor-control escapes in a tight loop, so suppressing it
    * when we suspect the output is being captured is doubly important.
    */
  def defaultAnimated: Boolean =
    defaultUseColor && !sys.env.contains("ORCA_NO_ANIMATION")

  private def consolePresent: Boolean = System.console() != null

  private def ciDetected: Boolean =
    sys.env.get("CI").exists(_.nonEmpty)
