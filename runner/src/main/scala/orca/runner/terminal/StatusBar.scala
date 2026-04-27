package orca.runner.terminal

import java.io.PrintStream
import java.util.concurrent.atomic.AtomicBoolean

/** A persistent single-line status indicator at the bottom of the
  * terminal, with the event log accumulating above. The status line
  * shows the current activity (stage name + spinner glyph); each log
  * write transparently scrolls it down by one row.
  *
  * Design:
  *   - All event-log output flows through [[appendLog]]. The bar
  *     clears its current status line, writes the log line, then
  *     re-draws the status below it.
  *   - The spinner runs on its own daemon thread, calling
  *     [[refreshFrame]] to advance the glyph in place without touching
  *     the log.
  *   - When [[animated]] is false (non-TTY output, redirected stderr,
  *     CI), the bar degrades to plain inline output: every appendLog
  *     just writes the line, and the spinner becomes a no-op. This
  *     keeps captured logs free of ANSI escapes.
  *   - All public methods are synchronised on `lock` so the renderer's
  *     main thread and the spinner's animator can interleave safely.
  *     `running` is the one field read outside the lock (the
  *     animator's loop check), which is why it stays an atomic — the
  *     other fields are plain `var`s under the lock.
  */
private[terminal] class StatusBar(
    out: PrintStream,
    useColor: Boolean,
    animated: Boolean = true,
    framePeriodMs: Long = 100L
):

  import StatusBar.{
    ClearLine,
    DefaultLabel,
    Frames,
    MaxStatusLineWidth,
    paint,
    truncateForOneLine
  }

  private val lock = new Object
  // `null` means "no status set"; non-null means "status visible at
  // the current cursor row, redraw on appendLog / refresh". Touched
  // only under `lock` so a plain var is sufficient for visibility.
  private var currentLabel: String | Null = null
  private var frameIndex: Int = 0
  private var animator: Option[Thread] = None
  // Read outside the lock by the animator's loop, so it stays atomic
  // for visibility. Concurrent start→stop races serialise on the
  // lock above; in the worst case an exiting animator thread and a
  // newly-spawned one briefly coexist, but both hold the lock to
  // touch the terminal so no torn frames result.
  private val running = new AtomicBoolean(false)

  /** Append a chunk of text to the event log above the status line.
    * The chunk may contain `\n`s; trailing newline is normalised so
    * each appended chunk ends one logical row. Empty input emits
    * just the trailing newline (used by callers as a section
    * separator).
    */
  def appendLog(text: String): Unit = lock.synchronized:
    if !animated || currentLabel == null then
      out.print(text)
      if !text.endsWith("\n") then out.println()
      out.flush()
    else
      // 1. Clear the status row so the log line lands cleanly.
      out.print(ClearLine)
      // 2. Print the log content with a guaranteed terminating newline.
      out.print(text)
      if !text.endsWith("\n") then out.println()
      // 3. Redraw the status one row below the just-written log.
      drawStatus()
      out.flush()

  /** Show (or relabel) the status line. Idempotent; calling twice
    * with the same label just refreshes the frame.
    *
    * When `animated = false` this is a no-op — non-TTY output has no
    * persistent bottom line, and the event log will have already
    * shown the same label as a `▶ <stage>` entry, so duplicating it
    * inline would just create noise.
    */
  def startStatus(label: String = DefaultLabel): Unit = lock.synchronized:
    if animated then
      val effective = if label.isEmpty then DefaultLabel else label
      currentLabel = effective
      drawStatus()
      out.flush()
      ensureAnimator()

  /** Hide the status line entirely. The cursor lands at the start of
    * the (now-cleared) status row, so subsequent writes start there.
    */
  def stopStatus(): Unit = lock.synchronized:
    val wasShown = currentLabel != null
    currentLabel = null
    if wasShown && animated then
      out.print(ClearLine)
      out.flush()
    running.set(false)
    val t = animator
    animator = None
    t.foreach(_.join(200))

  /** Called by the animator to redraw the spinner frame in place. */
  private def refreshFrame(): Unit = lock.synchronized:
    if currentLabel != null && animated then
      frameIndex = (frameIndex + 1) % Frames.size
      drawStatus()
      out.flush()

  private def drawStatus(): Unit =
    val label = currentLabel
    if label != null && animated then
      val frame = Frames(frameIndex)
      // Truncate to a single physical row's worth so the redraw on
      // each spinner tick stays anchored to one line. Without this,
      // a long stage name wraps the terminal, the next `\r[2K`
      // only clears the wrapped tail, and the user sees a stack of
      // partial spinner frames marching down the screen.
      val truncated = truncateForOneLine(label, MaxStatusLineWidth - frame.length - 2)
      out.print(ClearLine)
      out.print(paint(s"$frame $truncated", useColor))

  private def ensureAnimator(): Unit =
    if !running.getAndSet(true) then
      val t = new Thread(() => animateLoop(), "orca-statusbar")
      t.setDaemon(true)
      t.start()
      animator = Some(t)

  private def animateLoop(): Unit =
    while running.get() do
      Thread.sleep(framePeriodMs)
      if running.get() then refreshFrame()

private[terminal] object StatusBar:

  /** Carriage return + ANSI Erase-In-Line-2 (clear entire line). */
  // The ESC byte is ``; written explicitly via the unicode
  // escape so the constant survives copy-paste through tools that
  // strip control characters.
  private val ClearLine: String = "\r[2K"

  /** Default label when callers don't supply a more specific one. */
  private val DefaultLabel: String = "Thinking..."

  val Frames: Vector[String] =
    Vector("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

  /** Maximum characters in the rendered status line before we truncate.
    * Picked to fit a typical 80-column terminal without wrapping;
    * narrower terminals will wrap once on the truncation boundary,
    * which still avoids the staircase-spinner bug. Querying the real
    * terminal width would be cleaner but requires JLine's terminal
    * API and adds startup cost for marginal payoff.
    */
  private val MaxStatusLineWidth: Int = 78

  /** Single-line clamp: collapse all whitespace, then cut at `max`
    * with an ellipsis. Returning a string that still ends with a
    * partial multi-byte sequence is fine here — the only consumer is
    * the terminal, and we render through fansi which handles UTF-8.
    */
  private def truncateForOneLine(s: String, max: Int): String =
    val collapsed = s.replaceAll("\\s+", " ").trim
    if collapsed.length <= max then collapsed
    else s"${collapsed.take(max - 1)}…"

  private def paint(text: String, useColor: Boolean): String =
    if useColor then fansi.Color.DarkGray(text).render else text
