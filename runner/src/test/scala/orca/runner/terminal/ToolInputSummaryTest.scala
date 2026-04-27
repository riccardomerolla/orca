package orca.runner.terminal

class ToolInputSummaryTest extends munit.FunSuite:

  private val maxLen = 120

  test("summarise returns empty string for empty or `{}` input"):
    assertEquals(ToolInputSummary.summarise("", maxLen), "")
    assertEquals(ToolInputSummary.summarise("{}", maxLen), "")

  test("summarise picks file_path as the headline when present"):
    val raw = """{"file_path":"/tmp/foo.txt","other":"x"}"""
    assertEquals(ToolInputSummary.summarise(raw, maxLen), "(/tmp/foo.txt)")

  test("summarise relativises a file_path under workDir"):
    val workDir = os.Path("/tmp/orca-AbC")
    val raw = s"""{"file_path":"${workDir.toString}/src/Main.scala"}"""
    val out = ToolInputSummary.summarise(raw, maxLen, Some(workDir))
    assertEquals(out, "(src/Main.scala)")

  test("summarise leaves a file_path outside workDir absolute"):
    val workDir = os.Path("/tmp/orca-AbC")
    val raw = """{"file_path":"/etc/hosts"}"""
    val out = ToolInputSummary.summarise(raw, maxLen, Some(workDir))
    assertEquals(out, "(/etc/hosts)")

  test("summarise returns `.` when file_path equals workDir exactly"):
    val workDir = os.Path("/tmp/orca-AbC")
    val raw = s"""{"file_path":"${workDir.toString}"}"""
    val out = ToolInputSummary.summarise(raw, maxLen, Some(workDir))
    assertEquals(out, "(.)")

  test("summarise leaves command/pattern/query unchanged when they look pathy"):
    val workDir = os.Path("/tmp/orca-AbC")
    val raw = s"""{"command":"ls ${workDir.toString}/src"}"""
    val out = ToolInputSummary.summarise(raw, maxLen, Some(workDir))
    // `command` is free-form text; we don't try to relativise paths
    // embedded in shell commands.
    assert(
      out.contains(workDir.toString),
      s"expected absolute path preserved in command field; got: $out"
    )
