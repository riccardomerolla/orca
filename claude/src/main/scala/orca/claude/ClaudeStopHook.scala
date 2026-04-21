package orca.claude

/** Generates the files that wire a Claude Code session to Orca's
  * interactive-completion signal. Two files land under `workDir/.claude`:
  *   - `settings.json` — references the stop hook as a Stop event
  *   - `orca-stop-hook.sh` — bash+python script that scans the transcript for
  *     `<<<ORCA_DONE>>>`, writes the JSON payload to the sentinel file at
  *     `/tmp/orca-<session-id>.json`, and exits with code 2 to halt the turn.
  *     Orca watches for the sentinel file and reads the payload.
  */
object ClaudeStopHook:

  val SettingsRelativePath: os.SubPath = os.sub / ".claude" / "settings.json"
  val ScriptRelativePath: os.SubPath = os.sub / ".claude" / "orca-stop-hook.sh"

  def sentinelPath(sessionIdValue: String): os.Path =
    os.Path(s"/tmp/orca-$sessionIdValue.json")

  val settingsJson: String =
    """{
      |  "hooks": {
      |    "Stop": [
      |      {
      |        "matcher": "",
      |        "hooks": [
      |          {
      |            "type": "command",
      |            "command": "bash \"$CLAUDE_PROJECT_DIR/.claude/orca-stop-hook.sh\""
      |          }
      |        ]
      |      }
      |    ]
      |  }
      |}
      |""".stripMargin

  val scriptBody: String =
    """#!/usr/bin/env bash
      |set -eu
      |input=$(cat)
      |session_id=$(python3 -c 'import json,sys;print(json.load(sys.stdin)["session_id"])' <<<"$input")
      |transcript_path=$(python3 -c 'import json,sys;print(json.load(sys.stdin)["transcript_path"])' <<<"$input")
      |sentinel="/tmp/orca-$session_id.json"
      |
      |if ! grep -q '<<<ORCA_DONE>>>' "$transcript_path"; then
      |  exit 0
      |fi
      |
      |python3 - "$transcript_path" "$sentinel" <<'PY'
      |import json, sys
      |transcript_path, sentinel_path = sys.argv[1], sys.argv[2]
      |marker = "<<<ORCA_DONE>>>"
      |with open(transcript_path) as f:
      |    lines = f.readlines()
      |for line in reversed(lines):
      |    try:
      |        entry = json.loads(line)
      |    except json.JSONDecodeError:
      |        continue
      |    msg = entry.get("message", {})
      |    if msg.get("role") != "assistant":
      |        continue
      |    for block in msg.get("content", []):
      |        if block.get("type") == "text" and marker in block.get("text", ""):
      |            payload = block["text"].split(marker, 1)[1].strip()
      |            with open(sentinel_path, "w") as out:
      |                out.write(payload)
      |            sys.exit(0)
      |PY
      |
      |# Only halt the turn if we actually captured a payload. Otherwise the
      |# orchestrator would wait forever for a sentinel that never appears.
      |if [ -f "$sentinel" ]; then
      |  exit 2
      |else
      |  exit 0
      |fi
      |""".stripMargin

  def writeTo(workDir: os.Path): Unit =
    val settingsFile = workDir / SettingsRelativePath
    val scriptFile = workDir / ScriptRelativePath
    os.write.over(settingsFile, settingsJson, createFolders = true)
    os.write.over(scriptFile, scriptBody, createFolders = true)
    os.perms.set(scriptFile, "rwxr-xr-x")
