package orca.tools.pi

import scala.util.control.NonFatal

/** Best-effort close: swallow a teardown failure so it can't mask the turn's
  * real outcome. Shared by the backend (temp files) and the conversation
  * (process resources).
  */
private[pi] def closeQuietly(resource: AutoCloseable): Unit =
  try resource.close()
  catch case NonFatal(_) => ()
