package orca.subprocess

import java.util.concurrent.atomic.AtomicReference

/** CliRunner that hands out a pre-scripted [[FakePipedCliProcess]] on each
  * `spawnPiped` call. Records the args. Single-call: each prepared process is
  * consumed by exactly one spawn; running out throws so a test failure doesn't
  * drift into an unrelated stack.
  *
  * `run` is unsupported — drivers that need both shapes shouldn't share a
  * runner instance.
  */
class SpawnStubCliRunner(prepared: List[FakePipedCliProcess]) extends CliRunner:
  private val queue = new AtomicReference[List[FakePipedCliProcess]](prepared)
  private val recorded =
    new AtomicReference[List[SpawnStubCliRunner.SpawnCall]](Nil)

  def calls: List[List[String]] = spawnCalls.map(_.args)

  def spawnCalls: List[SpawnStubCliRunner.SpawnCall] = recorded.get().reverse

  def run(
      args: Seq[String],
      stdin: String,
      env: Map[String, String],
      cwd: os.Path
  ): CliResult =
    throw new UnsupportedOperationException(
      "SpawnStubCliRunner does not support run; the subject calls spawnPiped"
    )

  def spawnPiped(
      args: Seq[String],
      env: Map[String, String],
      cwd: os.Path,
      pipeStderr: Boolean
  ): PipedCliProcess =
    val _ = recorded.updateAndGet(
      SpawnStubCliRunner.SpawnCall(args.toList, env, cwd, pipeStderr) :: _
    )
    val next = queue
      .getAndUpdate(_.drop(1))
      .headOption
      .getOrElse(
        throw new IllegalStateException("ran out of prepared processes")
      )
    next

object SpawnStubCliRunner:
  case class SpawnCall(
      args: List[String],
      env: Map[String, String],
      cwd: os.Path,
      pipeStderr: Boolean
  )
