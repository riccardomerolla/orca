package orca.runner.terminal

import java.util.concurrent.atomic.AtomicInteger

/** Shared mutable depth counter consulted by both [[TerminalInteraction]]
  * (the OrcaEvent stage renderer) and [[TerminalConversationRenderer]]
  * (the per-conversation renderer). Each `StageStarted` event pushes the
  * counter; each `StageCompleted` pops it. The counter then dictates how
  * many leading spaces every printed line gets — nested stages indent
  * their content under the enclosing stage marker.
  *
  * `current` is the depth of the *content* under the most recent open
  * stage; the stage's own glyph line sits one level shallower.
  */
private[terminal] class StageDepth:
  private val depth = new AtomicInteger(0)

  /** Increment after a `StageStarted` is rendered (so the stage marker
    * itself uses the pre-increment depth and its content the post-).
    */
  def push(): Unit =
    val _ = depth.incrementAndGet()

  /** Decrement before a `StageCompleted` is rendered (so the closing
    * marker aligns with its opening one).
    */
  def pop(): Unit =
    val _ = depth.updateAndGet(d => math.max(0, d - 1))

  /** Indent string for the current depth. Two spaces per level — tight
    * enough that deeply-nested flows don't march off the right edge,
    * visible enough to separate stages. Stage markers print at this
    * indent before the matching push (start) or after the matching pop
    * (end), so opening and closing markers align with each other and
    * with the parent stage's content lines.
    */
  def contentIndent: String = "  " * depth.get()
