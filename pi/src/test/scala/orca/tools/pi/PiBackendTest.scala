package orca.tools.pi

import orca.backend.SystemPromptComposer
import orca.events.Usage
import orca.llm.{BackendTag, LlmConfig, Model, SessionId}
import orca.subprocess.{FakePipedCliProcess, SpawnStubCliRunner}

class PiBackendTest extends munit.FunSuite:

  private def sid: SessionId[BackendTag.Pi.type] =
    SessionId[BackendTag.Pi.type]("00000000-0000-0000-0000-000000000000")

  private def successfulProcess(
      message: String = "hello",
      inputTokens: Long = 1L,
      outputTokens: Long = 2L
  ): FakePipedCliProcess =
    val p = new FakePipedCliProcess()
    p.enqueueStdout(
      """{"type":"response","id":"orca-prompt","command":"prompt","success":true}"""
    )
    p.enqueueStdout(
      s"""{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"$message"}],"usage":{"input":$inputTokens,"output":$outputTokens},"model":"pi-model"}}"""
    )
    p.enqueueStdout("""{"type":"agent_end","messages":[]}""")
    p

  test(
    "runAutonomous starts pi RPC, writes prompt command, and returns stable session"
  ):
    val process = successfulProcess("answer", 10L, 5L)
    val runner = new SpawnStubCliRunner(List(process))
    val backend = new PiBackend(runner)
    val workDir = os.temp.dir()

    val result = backend.runAutonomous("do it", sid, LlmConfig.default, workDir)

    assertEquals(result.sessionId, sid)
    assertEquals(result.output, "answer")
    assertEquals(result.usage, Usage(10L, 5L, None))
    assertEquals(result.model.map(_.name), Some("pi-model"))

    val call = runner.spawnCalls.head
    assertEquals(call.cwd, workDir)
    assertEquals(call.pipeStderr, true)
    assert(call.args.containsSlice(Seq("pi", "--mode", "rpc")), call.args)
    assert(call.args.containsSlice(Seq("--session", SessionId.value(sid))))
    assert(process.writes.exists(_.contains("\"type\":\"prompt\"")))
    assert(process.writes.exists(_.contains("do it")))

  test("model and autonomous read-only config map to Pi flags"):
    val process = successfulProcess()
    val runner = new SpawnStubCliRunner(List(process))
    val backend = new PiBackend(runner)

    val _ = backend.runAutonomous(
      "q",
      sid,
      LlmConfig.default.copy(
        model = Some(Model("anthropic/claude-sonnet")),
        readOnly = true
      ),
      os.temp.dir()
    )

    val args = runner.calls.head
    assert(args.containsSlice(Seq("--model", "anthropic/claude-sonnet")), args)
    assert(args.containsSlice(Seq("--tools", "read,grep,find,ls")), args)
    assert(!args.contains("--extension"), args)

  test("interactive read-only config includes ask_user extension and tool"):
    val process = successfulProcess()
    val runner = new SpawnStubCliRunner(List(process))
    val backend = new PiBackend(runner)

    val conv = backend.runInteractive(
      "q",
      sid,
      displayPrompt = "q",
      LlmConfig.default.copy(readOnly = true),
      os.temp.dir(),
      outputSchema = Some("{}")
    )
    assert(conv.canAskUser)
    assertEquals(conv.outputSchema, Some("{}"))

    val args = runner.calls.head
    assert(
      args.containsSlice(Seq("--tools", "read,grep,find,ls,ask_user")),
      args
    )
    assert(args.contains("--extension"), args)

    val _ = conv.events.toList
    val _ = conv.awaitResult()

  test(
    "interactive system prompt file contains configured prompt, hint, and git rule"
  ):
    val process = new FakePipedCliProcess()
    val runner = new SpawnStubCliRunner(List(process))
    val backend = new PiBackend(runner)

    val conv = backend.runInteractive(
      "q",
      sid,
      displayPrompt = "q",
      LlmConfig.default.copy(systemPrompt = Some("be terse")),
      os.temp.dir(),
      outputSchema = None
    )

    val args = runner.calls.head
    val promptFile = args(args.indexOf("--append-system-prompt") + 1)
    val promptText = os.read(os.Path(promptFile))
    assert(promptText.contains("be terse"), promptText)
    assert(promptText.contains(PiAskUserExtension.Hint), promptText)
    assert(promptText.contains(SystemPromptComposer.RuntimeOwnsGit), promptText)

    val extensionFile = os.Path(args(args.indexOf("--extension") + 1))
    assert(os.exists(extensionFile))

    process.enqueueStdout(
      """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"done"}]}}"""
    )
    process.enqueueStdout("""{"type":"agent_end","messages":[]}""")
    val _ = conv.events.toList
    val _ = conv.awaitResult()
    assert(!os.exists(os.Path(promptFile)))
    assert(!os.exists(extensionFile))

  test("self-managed git suppresses the runtime git rule"):
    val process = successfulProcess()
    val runner = new SpawnStubCliRunner(List(process))
    val backend = new PiBackend(runner)

    val _ = backend.runAutonomous(
      "q",
      sid,
      LlmConfig.default.copy(selfManagedGit = true),
      os.temp.dir()
    )

    val args = runner.calls.head
    assert(!args.contains("--append-system-prompt"), args)
