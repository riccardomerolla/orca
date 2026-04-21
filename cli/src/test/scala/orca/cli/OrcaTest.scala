package orca.cli

import _root_.orca.*

import java.io.{ByteArrayOutputStream, PrintStream}

class OrcaTest extends munit.FunSuite:

  private def silentInteraction: TerminalInteraction =
    new TerminalInteraction(
      new PrintStream(new ByteArrayOutputStream()),
      useColor = false
    )

  test(
    "orca runs the body with a FlowContext carrying the provided userPrompt"
  ):
    var seen: String = ""
    orca(args = OrcaArgs("hello world"), interaction = silentInteraction) {
      seen = summon[FlowContext].userPrompt
    }
    assertEquals(seen, "hello world")

  test("orca wires the interaction's listeners so emit reaches them"):
    val buf = new ByteArrayOutputStream()
    val interaction =
      new TerminalInteraction(new PrintStream(buf), useColor = false)
    orca(interaction = interaction) {
      summon[FlowContext].emit(OrcaEvent.StageStarted("plan"))
    }
    assert(buf.toString.contains("plan"))
