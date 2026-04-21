package orca.cli

import java.io.PrintStream
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

/** 4-line animated "thinking" indicator: a label on line 1 and an orca
  * breaching a wave below it. Uses ANSI cursor-up to redraw the four body lines
  * in place. `start` is safe to call repeatedly — a new label replaces the
  * previous one; `stop` clears the frame so subsequent terminal output doesn't
  * see the partially-drawn animation.
  */
class OrcaSpinner(out: PrintStream, framePeriodMs: Long = 180L):

  import OrcaSpinner.Frames

  private val running = new AtomicBoolean(false)
  private val animator: AtomicReference[Option[Thread]] =
    AtomicReference(None)

  def start(label: String): Unit =
    // If already running, clear the previous frame so the new label lands
    // at a clean spot.
    if running.get() then stop()
    running.set(true)
    out.println(s"⏳ $label")
    Frames.head.foreach(out.println)
    out.flush()
    val t = new Thread(() => animate(), "orca-spinner")
    t.setDaemon(true)
    t.start()
    animator.set(Some(t))

  def stop(): Unit =
    if !running.getAndSet(false) then ()
    else
      animator.getAndSet(None).foreach(_.join(500))
      // Move cursor up 5 lines (label + 4 animation) and erase below.
      out.print("[5A[0J")
      out.flush()

  private def animate(): Unit =
    var idx = 0
    while running.get() do
      Thread.sleep(framePeriodMs)
      if running.get() then
        idx += 1
        val frame = Frames(idx % Frames.size)
        // Cursor up 4 lines (over the body), redraw each with clear-to-EOL.
        out.print("[4A")
        frame.foreach(line => out.println(s"[2K$line"))
        out.flush()

object OrcaSpinner:

  /** Each frame is exactly 4 lines of the same width so ANSI in-place redraw
    * leaves no stray characters. Frames depict a single orca breaching
    * left-to-right across the waves.
    */
  val Frames: Vector[Vector[String]] = Vector(
    Vector(
      "                                            ",
      "                                            ",
      "      __                                    ",
      "   ~~/_o>~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~"
    ),
    Vector(
      "                                            ",
      "           __                               ",
      "          /_o>                              ",
      "   ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~"
    ),
    Vector(
      "                 __                         ",
      "                /_o>                        ",
      "                                            ",
      "   ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~"
    ),
    Vector(
      "                       __                   ",
      "                      /_o>                  ",
      "                                            ",
      "   ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~"
    ),
    Vector(
      "                                            ",
      "                             __             ",
      "                            /_o>            ",
      "   ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~"
    ),
    Vector(
      "                                            ",
      "                                            ",
      "                                  __        ",
      "   ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~/_o>~~~~~~~"
    ),
    Vector(
      "                                            ",
      "                                            ",
      "                                            ",
      "   ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~"
    )
  )
