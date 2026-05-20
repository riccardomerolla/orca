package orca.runner.terminal

import orca.backend.{Conversation, Interaction, LlmResult}
import orca.events.{OrcaEvent, OrcaListener}
import orca.llm.BackendTag

import java.io.PrintStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{CompletableFuture, LinkedBlockingQueue}
import scala.util.control.NonFatal

/** Terminal-based `Interaction`. Renders stage transitions, tool uses,
  * streaming LLM output, and errors to a `PrintStream` (defaults to stderr so
  * the structured output on stdout stays clean).
  *
  * The output is split in two zones:
  *   - The **event log** at the top, growing line-by-line as stages start and
  *     tools fire.
  *   - A **status line** pinned at the bottom, showing the current activity
  *     with an animated spinner glyph.
  *
  * Both are owned by [[StatusBar]]: each event-log write transparently scrolls
  * the status row down by one. When the renderer doesn't own a TTY (CI,
  * redirected stderr, `NO_COLOR`/`ORCA_NO_ANIMATION`), the `StatusBar` degrades
  * to plain inline output without ANSI escapes.
  *
  * Unicode glyphs require a UTF-8 locale; on platforms with a non-UTF-8 default
  * charset the caller should pass a PrintStream constructed with `new
  * PrintStream(out, true, "UTF-8")`.
  *
  * Internally: a single daemon virtual thread serializes every access to the
  * underlying [[TerminalRendererState]]. Listener events are submitted with
  * `tell`-style fire-and-forget; `drive` submits an `ask`-style request and
  * blocks the caller until the worker finishes the conversation. While `drive`
  * runs, listener events queue and flush in order after it returns (which is
  * fine — the conversation renderer owns the terminal during a driven session).
  * [[close]] drains any pending submissions and joins the worker; the runtime
  * calls it from `flow(...)` in `finally`.
  */
class TerminalInteraction(
    out: PrintStream = System.err,
    useColor: Boolean = TerminalInteraction.defaultUseColor,
    animated: Boolean = TerminalInteraction.defaultAnimated,
    workDir: Option[os.Path] = None
) extends Interaction:

  private val state =
    new TerminalRendererState(out, useColor, animated, workDir)

  /** Sealed task type for the worker mailbox: a `TerminalRendererState => Unit`
    * thunk, or a sentinel that tells the worker to exit.
    */
  private sealed trait Task
  private case class Run(f: TerminalRendererState => Unit) extends Task
  private case object Stop extends Task

  private val mailbox = new LinkedBlockingQueue[Task]()

  private val closed = new AtomicBoolean(false)

  private val worker: Thread = Thread
    .ofVirtual()
    .name("orca-terminal-renderer")
    .start: () =>
      var running = true
      while running do
        mailbox.take() match
          case Stop => running = false
          case Run(f) =>
            try f(state)
            catch
              // Interrupt = wind down cleanly. Restore the interrupt flag
              // so anything else on this thread observes it.
              case _: InterruptedException =>
                Thread.currentThread().interrupt()
                running = false
              // Recoverable renderer-side bug — log to stderr (best-effort,
              // since stderr might be what we just failed to write to) and
              // keep draining the mailbox so the flow can proceed to a clean
              // shutdown. Fatal throwables (OOM, StackOverflow) propagate
              // and let the JVM die loudly.
              case NonFatal(t) =>
                System.err.println(
                  s"[orca-terminal-renderer] swallowed: $t"
                )

  private val listenersList: List[OrcaListener] = List: (e: OrcaEvent) =>
    val _ = mailbox.put(Run(_.onEvent(e)))

  def listeners: List[OrcaListener] = listenersList

  /** Drive a live conversation to completion on the worker thread. Blocks the
    * caller (the flow's main thread) until the conversation finishes; queued
    * listener events behind this request will be processed afterwards.
    *
    * If the caller is interrupted while waiting, the `InterruptedException`
    * propagates — the worker continues processing the conversation to its
    * natural end (no fine-grained mid-render cancellation today; cancel via
    * `Conversation.cancel` upstream if you need that).
    */
  def drive[B <: BackendTag](conversation: Conversation[B]): LlmResult[B] =
    val reply = new CompletableFuture[LlmResult[B]]()
    val _ = mailbox.put(Run { st =>
      try
        val _ = reply.complete(st.driveConversation(conversation))
      catch
        case t: Throwable =>
          val _ = reply.completeExceptionally(t)
    })
    try reply.get()
    catch
      case e: java.util.concurrent.ExecutionException =>
        throw Option(e.getCause).getOrElse(e)

  /** Enqueue `Stop` behind any pending work and wait for the worker to drain
    * and exit. Idempotent — repeated calls return immediately once the worker
    * has stopped.
    */
  override def close(): Unit =
    if closed.compareAndSet(false, true) then
      val _ = mailbox.put(Stop)
      worker.join()

object TerminalInteraction:
  val StageStartGlyph: String = "▶"
  val StageDoneGlyph: String = "✔"
  val ErrorGlyph: String = "✖"

  /** Stages, steps, and structured-result summaries share the same magenta-bold
    * glyph — the dominant accent for "primary content" in the event log. Pulled
    * into a constant so the three render paths can't drift.
    */
  val StepGlyphStyle: fansi.Attrs = fansi.Color.Magenta ++ fansi.Bold.On

  /** ANSI colors default off when stderr isn't attached to a terminal (no
    * controlling console), the `NO_COLOR` convention is honoured, or we detect
    * a CI runner.
    */
  def defaultUseColor: Boolean =
    !sys.env.contains("NO_COLOR") && consolePresent && !ciDetected

  /** Animation is strictly a subset of colour — it additionally writes
    * cursor-control escapes in a tight loop, so suppressing it when we suspect
    * the output is being captured is doubly important.
    */
  def defaultAnimated: Boolean =
    defaultUseColor && !sys.env.contains("ORCA_NO_ANIMATION")

  private def consolePresent: Boolean = System.console() != null

  private def ciDetected: Boolean =
    sys.env.get("CI").exists(_.nonEmpty)
