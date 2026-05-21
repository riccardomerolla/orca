package orca.runner.terminal

import ox.{Ox, forever, forkDiscard, sleep}
import ox.channels.{Actor, ActorRef, BufferCapacity}

import java.io.PrintStream
import scala.collection.immutable.Queue
import scala.concurrent.duration.DurationLong

/** The terminal rendering surface. Owns `out` and serialises every write
  * through an internal Ox actor; a spinner-animator `forkDiscard` `tell`s the
  * same actor periodically. The actor's worker is the only thread that touches
  * `out`, so log lines, status redraws, and tick frames can never interleave
  * mid-write.
  *
  * The animator runs on a separate fork from any caller, so the spinner
  * continues to advance even while `drive` is parked in a backend conversation
  * iterator on a different thread.
  *
  * Animated vs plain: when `animated = false`, the actor still serialises
  * writes but `setStatus`/`tick` become no-ops, the animator fork isn't
  * spawned, and `log` writes inline without ANSI escapes.
  *
  * **Suspend protocol.** `suspendStatus()` clears the status row and starts
  * buffering subsequent `log` tells; `resumeStatus()` drains the buffer and
  * unpauses the animator. Used by the approval prompt so live event output
  * doesn't scribble on top of `readLine`.
  */
class TerminalOutput private[terminal] (
    actor: ActorRef[TerminalOutputState]
):

  /** Append a (possibly multi-line) chunk to the event log. Trailing newline is
    * normalised. Empty input emits a single newline separator.
    */
  def log(text: String): Unit = actor.tell(_.log(text))

  /** Show / relabel / hide the status row. `None` hides it. */
  def setStatus(label: Option[String]): Unit =
    actor.tell(_.setStatus(label))

  /** Block until pending writes have drained, clear the status row, and route
    * subsequent `log` tells into a buffer. The animator stops drawing until
    * [[resumeStatus]] is called. Use around a `readLine` prompt.
    */
  def suspendStatus(): Unit = actor.ask(_.suspend())

  /** Drain the buffer (writes the suspended log lines to `out`) and re-enable
    * the animator. Pair with [[suspendStatus]] in `try/finally`.
    */
  def resumeStatus(): Unit = actor.ask(_.resume())

  /** Flush pending tells, clear the status row, and release the renderer.
    * Swallow close-time throws so they don't mask whatever already failed
    * upstream. The animator fork is interrupted by scope teardown.
    */
  def close(): Unit =
    try actor.ask(_.shutdown())
    catch case _: Throwable => ()

object TerminalOutput:

  /** Build a `TerminalOutput` and start its actor + animator in the given
    * scope. The animator is `forkDiscard`: scope-end interrupts it. The IE from
    * `ox.sleep` propagates out of `forever` and is absorbed by the supervisor
    * (scope is already winding down).
    */
  def start(
      out: PrintStream,
      useColor: Boolean,
      animated: Boolean,
      framePeriodMs: Long = 100L
  )(using Ox, BufferCapacity): TerminalOutput =
    val state = new TerminalOutputState(out, useColor, animated)
    val actor = Actor.create(state)
    if animated then
      forkDiscard:
        forever:
          sleep(framePeriodMs.millis)
          actor.tell(_.tick())
    new TerminalOutput(actor)

/** Mutable rendering state for [[TerminalOutput]]. Not thread-safe in isolation
  * — production code wraps it in an Ox actor; tests construct it directly to
  * drive rendering synchronously.
  */
private[terminal] class TerminalOutputState(
    out: PrintStream,
    useColor: Boolean,
    animated: Boolean
):

  import TerminalOutputState.{
    ClearLine,
    DefaultLabel,
    Frames,
    MaxStatusLineWidth,
    paint
  }

  private var currentLabel: Option[String] = None
  private var frameIndex: Int = 0
  // When `suspended`, the status row is cleared and `log` calls accumulate
  // into `suspendedBuffer` instead of writing to `out`. The animator
  // short-circuits, so no ticks land while a prompt is being read.
  private var suspended: Boolean = false
  private var suspendedBuffer: Queue[String] = Queue.empty

  def log(text: String): Unit =
    if suspended then suspendedBuffer = suspendedBuffer.enqueue(text)
    else writeLog(text)

  def setStatus(label: Option[String]): Unit =
    if animated then
      val effective = label.map(s => if s.isEmpty then DefaultLabel else s)
      effective match
        case None =>
          val wasShown = currentLabel.isDefined
          currentLabel = None
          if wasShown then
            out.print(ClearLine)
            out.flush()
        case some @ Some(_) =>
          currentLabel = some
          drawStatus()
          out.flush()

  def tick(): Unit =
    if animated && !suspended && currentLabel.isDefined then
      frameIndex = (frameIndex + 1) % Frames.size
      drawStatus()
      out.flush()

  def suspend(): Unit =
    if !suspended then
      suspended = true
      // Clear the status row so the prompt can land cleanly. We keep
      // `currentLabel` so resume can redraw the same status without callers
      // having to remember it.
      if animated && currentLabel.isDefined then
        out.print(ClearLine)
        out.flush()

  def resume(): Unit =
    if suspended then
      val toDrain = suspendedBuffer
      suspendedBuffer = Queue.empty
      suspended = false
      toDrain.foreach(writeLog)
      if animated && currentLabel.isDefined then
        drawStatus()
        out.flush()

  def shutdown(): Unit =
    // Drain any buffered log lines so they don't get dropped on shutdown.
    if suspendedBuffer.nonEmpty then
      val toDrain = suspendedBuffer
      suspendedBuffer = Queue.empty
      suspended = false
      toDrain.foreach(writeLog)
    val wasShown = currentLabel.isDefined
    currentLabel = None
    if wasShown && animated then
      out.print(ClearLine)
      out.flush()

  private def writeLog(text: String): Unit =
    if !animated || currentLabel.isEmpty then
      out.print(text)
      if !text.endsWith("\n") then out.println()
      out.flush()
    else
      out.print(ClearLine)
      out.print(text)
      if !text.endsWith("\n") then out.println()
      drawStatus()
      out.flush()

  private def drawStatus(): Unit =
    currentLabel.foreach: label =>
      if animated then
        val frame = Frames(frameIndex)
        // Truncate to a single physical row so each redraw stays anchored to
        // one line — otherwise a long label wraps and the next clear-line
        // only erases the wrapped tail, leaving a staircase of frames.
        val truncated =
          Text.oneLine(label, MaxStatusLineWidth - frame.length - 2)
        out.print(ClearLine)
        out.print(paint(s"$frame $truncated", useColor))

private[terminal] object TerminalOutputState:

  /** Carriage return + ANSI Erase-In-Line-2 (clear entire line). The ESC
    * character is the literal byte ``.
    */
  private val ClearLine: String = "\r[2K"

  private val DefaultLabel: String = "Thinking..."

  val Frames: Vector[String] =
    Vector("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

  /** Maximum characters in the rendered status line before truncation. */
  private val MaxStatusLineWidth: Int = 78

  private def paint(text: String, useColor: Boolean): String =
    Ansi.paint(useColor, fansi.Color.DarkGray, text)
