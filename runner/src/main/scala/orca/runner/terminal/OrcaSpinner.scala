package orca.runner.terminal

import java.io.PrintStream
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

/** 2-line animated "thinking" indicator: a label on line 1, and a single line
  * of water on line 2 with orca fins poking up through the surface and
  * drifting left-to-right. Uses ANSI cursor-up to redraw the water line in
  * place. `start` is safe to call repeatedly — a new label replaces the
  * previous one; `stop` clears the frame so subsequent terminal output doesn't
  * see the partially-drawn animation.
  *
  * The fins shift by one column per frame; because they're evenly spaced,
  * after `Spacing` frames the pattern aligns with itself again and the loop is
  * seamless. With `useColor = true`, fins render bold white, the water cyan,
  * and the label yellow.
  */
class OrcaSpinner(
    out: PrintStream,
    framePeriodMs: Long = 180L,
    useColor: Boolean = true
):

  import OrcaSpinner.{Frames, colorize, paintLabel}

  private val running = new AtomicBoolean(false)
  private val animator: AtomicReference[Option[Thread]] =
    AtomicReference(None)

  def start(label: String): Unit =
    if running.get() then stop()
    running.set(true)
    out.println(paintLabel(label, useColor))
    out.println(colorize(Frames.head, useColor))
    out.flush()
    val t = new Thread(() => animate(), "orca-spinner")
    t.setDaemon(true)
    t.start()
    animator.set(Some(t))

  def stop(): Unit =
    if !running.getAndSet(false) then ()
    else
      animator.getAndSet(None).foreach(_.join(500))
      // Move cursor up 2 lines (label + water) and erase below.
      out.print("[2A[0J")
      out.flush()

  private def animate(): Unit =
    var idx = 0
    while running.get() do
      Thread.sleep(framePeriodMs)
      if running.get() then
        idx += 1
        val frame = Frames(idx % Frames.size)
        // Cursor up 1 line (over the water), redraw with clear-to-EOL.
        out.print("[1A[2K")
        out.println(colorize(frame, useColor))
        out.flush()

object OrcaSpinner:

  private val Width: Int = 44
  private val Spacing: Int = 8
  private val Fin: Char = '^'

  /** `Spacing` frames of a single water line with fins poking through, one
    * per column the fins travel before the pattern re-aligns with itself.
    */
  val Frames: Vector[String] =
    (0 until Spacing).toVector.map(waterWithFins)

  private def waterWithFins(phase: Int): String =
    val buf = Array.fill(Width)('~')
    var pos = phase
    while pos < Width do
      buf(pos) = Fin
      pos += Spacing
    new String(buf)

  private val FinPattern = s"\\${Fin}+".r
  private val WavePattern = "~+".r

  /** Apply fansi colors to a frame line: fins become bold white, runs of `~`
    * become cyan.
    */
  def colorize(line: String, useColor: Boolean): String =
    if !useColor then line
    else
      val withFins =
        FinPattern.replaceAllIn(line, m => fansi.Bold.On(m.matched).render)
      WavePattern.replaceAllIn(
        withFins,
        m => fansi.Color.Cyan(m.matched).render
      )

  /** The status line above the animation. Yellow so it stands out against the
    * cyan water and white fins.
    */
  def paintLabel(label: String, useColor: Boolean): String =
    val text = s"⌛ $label"
    if useColor then fansi.Color.Yellow(text).render else text
