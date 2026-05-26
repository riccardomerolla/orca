package orca.llm

/** Stateful wrapper around an [[LlmTool]]'s autonomous text path that lazily
  * starts a session on the first call and continues it thereafter. Removes
  * the "first task starts, rest continue" match-on-Option boilerplate that
  * shows up in every multi-step flow:
  *
  * {{{
  * val coder = claude.session
  * for task <- plan.tasks do
  *   val sid = coder.run(task.description)  // starts on first call
  *   reviewAndFixLoop(coder = claude, sessionId = sid, ...)
  * }}}
  *
  * The session id is observable via [[id]] for steps that happen after the
  * loop (e.g. a documentation pass that wants the cross-task context). Not
  * thread-safe — flow scripts are single-threaded at the script level, and a
  * session's continuations are sequential by nature.
  */
class Session[B <: BackendTag](llm: LlmTool[B]):

  private var current: Option[SessionId[B]] = None

  /** Run `prompt` against this session. First call starts a fresh session,
    * subsequent calls continue it. Returns the (stable) session id so
    * downstream helpers like `reviewAndFixLoop` can target the same session.
    */
  def run(
      prompt: String,
      config: LlmConfig = LlmConfig.default
  ): SessionId[B] =
    current match
      case Some(sid) =>
        val _ = llm.autonomous.continueSession(sid, prompt, config)
        sid
      case None =>
        val (sid, _) = llm.autonomous.startSession(prompt, config)
        current = Some(sid)
        sid

  /** The session id, if `run` has been called at least once. `None` before
    * the first call — useful for "skip this step if there was no work" guards
    * around post-loop continuations.
    */
  def id: Option[SessionId[B]] = current
