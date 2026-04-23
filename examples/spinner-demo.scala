//> using dep "com.virtuslab::orca:0.1.0-SNAPSHOT"
//> using repository ivy2Local
//> using jvm 21

import orca.runner.terminal.OrcaSpinner

/** Standalone showcase of Orca's 4-line orca-and-wave spinner.
  *
  * Cycles through a few fake stages a few seconds each, re-labelling the
  * spinner in place between them, then stops cleanly. Run from the repo
  * root:
  *
  * ```bash
  * sbt publishLocal           # once, to install com.virtuslab::orca locally
  * scala-cli run examples/spinner-demo.scala
  * ```
  */
@main def spinnerDemo(): Unit =
  val spinner = new OrcaSpinner(System.err)
  val stages = List(
    "Planning the feature"   -> 3000L,
    "Writing the code"       -> 4000L,
    "Running the test suite" -> 2500L,
    "Opening the pull request" -> 2000L
  )

  for (label, millis) <- stages do
    spinner.start(label)
    Thread.sleep(millis)

  spinner.stop()
  println("✔ Demo complete. The spinner is now idle.")
