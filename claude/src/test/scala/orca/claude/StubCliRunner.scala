package orca.claude

import java.util.concurrent.atomic.AtomicReference

case class CliCall(
    args: List[String],
    stdin: String,
    env: Map[String, String],
    cwd: os.Path
)

case class SpawnCall(
    args: List[String],
    env: Map[String, String],
    cwd: os.Path
)

/** A `CliRunner` that returns a pre-configured response and records every
  * invocation for later assertions. Test helper — mutable state is confined to
  * AtomicReferences.
  */
class StubCliRunner(
    response: CliResult = CliResult(0, "", ""),
    processFactory: () => CliProcess = () => NoopCliProcess
) extends CliRunner:
  private val recordedCalls: AtomicReference[List[CliCall]] =
    AtomicReference(Nil)
  private val recordedSpawns: AtomicReference[List[SpawnCall]] =
    AtomicReference(Nil)

  // Calls are prepended (newest-first) and reversed for chronological access.
  def calls: List[CliCall] = recordedCalls.get().reverse
  def lastCall: Option[CliCall] = recordedCalls.get().headOption

  def spawns: List[SpawnCall] = recordedSpawns.get().reverse
  def lastSpawn: Option[SpawnCall] = recordedSpawns.get().headOption

  def run(
      args: Seq[String],
      stdin: String,
      env: Map[String, String],
      cwd: os.Path
  ): CliResult =
    val _ =
      recordedCalls.updateAndGet(cs =>
        CliCall(args.toList, stdin, env, cwd) :: cs
      )
    response

  def spawn(
      args: Seq[String],
      env: Map[String, String],
      cwd: os.Path
  ): CliProcess =
    val _ =
      recordedSpawns.updateAndGet(ss => SpawnCall(args.toList, env, cwd) :: ss)
    processFactory()

object NoopCliProcess extends CliProcess:
  def sendSigInt(): Unit = ()
  def isAlive: Boolean = false
  def waitForExit(): Int = 0
