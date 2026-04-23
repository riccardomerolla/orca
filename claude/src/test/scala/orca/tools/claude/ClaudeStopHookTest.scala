package orca.tools.claude

import io.circe.parser.parse as parseJson

class ClaudeStopHookTest extends munit.FunSuite:

  private def prepared: os.Path =
    val dir = os.temp.dir()
    ClaudeStopHook.writeTo(dir)
    dir

  test("writes both settings.json and the hook script under .claude/"):
    val dir = prepared
    assert(os.exists(dir / ClaudeStopHook.SettingsRelativePath))
    assert(os.exists(dir / ClaudeStopHook.ScriptRelativePath))

  test("hook script is executable"):
    val script = prepared / ClaudeStopHook.ScriptRelativePath
    assert(
      os.perms(script).toString.contains("x"),
      "hook script must be executable"
    )

  test("settings.json is valid JSON and wires the hook script path"):
    val settings = os.read(prepared / ClaudeStopHook.SettingsRelativePath)
    assert(
      parseJson(settings).isRight,
      s"settings.json is not valid JSON: $settings"
    )
    assert(settings.contains("$CLAUDE_PROJECT_DIR/.claude/orca-stop-hook.sh"))

  test("hook script references the ORCA_DONE marker and the sentinel file"):
    val script = os.read(prepared / ClaudeStopHook.ScriptRelativePath)
    assert(script.contains("<<<ORCA_DONE>>>"))
    assert(script.contains("/tmp/orca-$session_id.json"))

  test("hook script only exits 2 when the sentinel was written"):
    val script = os.read(prepared / ClaudeStopHook.ScriptRelativePath)
    assert(script.contains("""if [ -f "$sentinel" ]; then"""))
    assert(script.contains("exit 2"))
    assert(script.contains("exit 0"))
