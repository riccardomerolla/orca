package orca.runner.terminal

/** Mutable depth counter consulted by [[TerminalRendererState]] (the OrcaEvent
  * stage listener) and [[TerminalConversationRenderer]] (the per-conversation
  * renderer). Each `StageStarted` event pushes the counter; each
  * `StageCompleted` pops it. The counter dictates how many leading spaces every
  * printed line gets — nested stages indent their content under the enclosing
  * stage marker.
  *
  * Not thread-safe on its own. Both consumers access it only from
  * `TerminalInteraction`'s single worker thread, which serialises every
  * push/pop and indent snapshot — no atomics or locks needed here.
  */
private[terminal] class StageDepth:
  private var depth: Int = 0

  /** Increment after a `StageStarted` is rendered. Stage markers print *before*
    * the matching push (and after the matching pop on close), so opening and
    * closing markers align with the parent stage's content indent.
    */
  def push(): Unit = depth += 1

  /** Decrement before a `StageCompleted` is rendered. Clamped at zero so a
    * stray `pop` from a malformed event stream can't wrap into a giant indent.
    */
  def pop(): Unit = depth = math.max(0, depth - 1)

  /** Indent string for the current depth. Two spaces per level — tight enough
    * that deeply-nested flows don't march off the right edge, visible enough to
    * separate stages.
    */
  def contentIndent: String = "  " * depth
