package orca.runner

import java.io.PrintStream

/** The one-line-of-identity startup banner: the running version and where this
  * run's trace log lives. Printed once to the console at flow start. Pure ASCII
  * so it survives a non-UTF-8 console.
  */
private[orca] object OrcaBanner:

  /** orca's version from the jar manifest (`Implementation-Version`, written by
    * sbt's `packageBin`). `"dev"` when running from class directories (a local
    * `sbt run` or the test suite), where there's no jar manifest.
    */
  def version: String =
    Option(getClass.getPackage.getImplementationVersion).getOrElse("dev")

  /** Print the `Orca <version>, logs: <path>` line to `out`. */
  def print(out: PrintStream, logFile: os.Path): Unit =
    out.println(s"Orca $version, logs: $logFile")
    out.println()
