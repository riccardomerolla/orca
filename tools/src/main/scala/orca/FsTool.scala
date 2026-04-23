package orca

/** Filesystem adapter usable from flow scripts — the handle behind the `fs`
  * accessor. Reads, writes, and globs files against the flow's working
  * directory.
  */
trait FsTool:
  def read(path: String): String
  def write(path: String, content: String): Unit
  def list(glob: String): List[String]
