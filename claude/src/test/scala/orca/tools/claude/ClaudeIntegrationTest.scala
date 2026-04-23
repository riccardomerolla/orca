package orca.tools.claude

import orca.{ConversationEvent, LlmConfig, SessionId}
import orca.subprocess.OsProcCliRunner

/** End-to-end tests against the real `claude` CLI. Gated on the
  * `ORCA_INTEGRATION` environment variable so `sbt test` without the flag
  * behaves like a pure unit suite. Require `claude` to be installed and
  * authenticated on the host.
  */
class ClaudeIntegrationTest extends munit.FunSuite:

  override def munitTests(): Seq[Test] =
    if sys.env.contains("ORCA_INTEGRATION") then super.munitTests()
    else Nil

  override def munitTimeout: scala.concurrent.duration.Duration =
    import scala.concurrent.duration.DurationInt
    5.minutes

  private val backend = new ClaudeBackend(OsProcCliRunner)

  test("headless prompt returns the requested literal output"):
    val result = backend.runHeadless(
      prompt = "Reply with the single word: READY",
      config = LlmConfig.default,
      workDir = os.temp.dir()
    )
    assert(
      result.output.contains("READY"),
      s"expected output to contain READY, got: ${result.output}"
    )
    assert(SessionId.value(result.sessionId).nonEmpty)

  test("continueHeadless carries conversational context across turns"):
    val workDir = os.temp.dir()
    val first = backend.runHeadless(
      prompt = "Remember the number 42. Reply with: stored.",
      config = LlmConfig.default,
      workDir = workDir
    )
    val second = backend.continueHeadless(
      sessionId = first.sessionId,
      prompt = "What number did I ask you to remember?",
      config = LlmConfig.default,
      workDir = workDir
    )
    assert(
      second.output.contains("42"),
      s"expected resumed session to recall '42', got: ${second.output}"
    )

  test("stream-json interactive session reaches a Result with a session id"):
    val conversation = backend.runInteractive(
      prompt = "Reply with just the number 7. Nothing else.",
      config = LlmConfig.default,
      workDir = os.temp.dir(),
      outputSchema = None
    )
    try
      // Drain events so the driver can process them; we don't render
      // anything in the integration test — awaitResult gives the outcome.
      conversation.events.foreach(_ => ())
      val result = conversation.awaitResult()
      assert(
        result.output.contains("7"),
        s"expected a reply containing '7', got: ${result.output}"
      )
      assert(SessionId.value(result.sessionId).nonEmpty)
    finally conversation.cancel()

  test("stream-json session emits text deltas as the agent streams"):
    val conversation = backend.runInteractive(
      prompt =
        "Count from 1 to 5, one per line, then stop. Do not emit anything else.",
      config = LlmConfig.default,
      workDir = os.temp.dir(),
      outputSchema = None
    )
    try
      val events = conversation.events.toList
      val _ = conversation.awaitResult()
      assert(
        events.exists(_.isInstanceOf[ConversationEvent.AssistantTextDelta]),
        s"expected at least one AssistantTextDelta; got: $events"
      )
      assert(
        events.exists(_ == ConversationEvent.AssistantTurnEnd),
        s"expected an AssistantTurnEnd; got: $events"
      )
    finally conversation.cancel()
