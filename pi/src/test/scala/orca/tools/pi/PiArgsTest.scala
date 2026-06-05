package orca.tools.pi

import orca.llm.{BackendTag, LlmConfig, Model, SessionId}

class PiArgsTest extends munit.FunSuite:

  private val sid: SessionId[BackendTag.Pi.type] =
    SessionId[BackendTag.Pi.type]("sid")

  test("rpc args include mode and session id"):
    val args = PiArgs.rpc(sid, LlmConfig.default, None)
    assertEquals(
      args.take(5),
      Seq("pi", "--mode", "rpc", "--session-id", "sid")
    )

  test("model and system prompt file are rendered"):
    val args = PiArgs.rpc(
      sid,
      LlmConfig.default.copy(model = Some(Model("openai/gpt-5"))),
      Some(os.Path("/tmp/system.md"))
    )
    assert(args.containsSlice(Seq("--model", "openai/gpt-5")), args)
    assert(
      args.containsSlice(Seq("--append-system-prompt", "/tmp/system.md")),
      args
    )

  test("read-only tools exclude writes"):
    val args = PiArgs.rpc(sid, LlmConfig.default.copy(readOnly = true), None)
    assert(args.containsSlice(Seq("--tools", "read,grep,find,ls")), args)

  test("interactive ask-user extension adds extension and ask_user tool"):
    val args = PiArgs.rpc(
      sid,
      LlmConfig.default.copy(readOnly = true),
      None,
      askUserExtension = Some(os.Path("/tmp/ask-user.ts"))
    )
    assert(
      args.containsSlice(Seq("--tools", "read,grep,find,ls,ask_user")),
      args
    )
    assert(args.containsSlice(Seq("--extension", "/tmp/ask-user.ts")), args)
