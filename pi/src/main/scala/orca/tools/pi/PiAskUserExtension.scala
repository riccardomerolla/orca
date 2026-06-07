package orca.tools.pi

import scala.util.control.NonFatal

/** Temporary Pi extension that exposes Orca's backend-agnostic `ask_user`
  * conversation event through Pi's native extension UI protocol.
  *
  * The extension intentionally has no imports so it can be written to a temp
  * directory and loaded by Pi without relying on Node module resolution from
  * that directory. The `parameters` value is plain JSON Schema / TypeBox shape,
  * which Pi accepts for tool schemas.
  */
private[pi] final class PiAskUserExtension private (
    val dir: os.Path,
    val file: os.Path
) extends AutoCloseable:
  def close(): Unit =
    try os.remove.all(dir)
    catch case NonFatal(_) => ()

private[pi] object PiAskUserExtension:

  val ToolName: String = "ask_user"

  val Hint: String =
    "If you need a concise clarification from the human before continuing, " +
      s"call the `$ToolName` tool with a clear question. Use it sparingly; " +
      "do not ask if you can make a reasonable assumption."

  def allocate(): PiAskUserExtension =
    val dir = os.temp.dir(prefix = "orca-pi-ask-user-", deleteOnExit = true)
    val file = dir / "ask-user.ts"
    os.write(file, Source)
    new PiAskUserExtension(dir, file)

  private val Source: String =
    s"""
       |export default function(pi) {
       |  pi.registerTool({
       |    name: "$ToolName",
       |    label: "Ask User",
       |    description: "Ask the human user one concise clarifying question and wait for their answer.",
       |    promptSnippet: "Ask the user a clarifying question when necessary",
       |    promptGuidelines: [
       |      "Use $ToolName only when a human answer is required to proceed.",
       |      "Ask exactly one concise, actionable question.",
       |      "Do not use $ToolName for information you can infer or inspect yourself."
       |    ],
       |    parameters: {
       |      type: "object",
       |      properties: {
       |        question: {
       |          type: "string",
       |          description: "The concise question to ask the human user."
       |        }
       |      },
       |      required: ["question"],
       |      additionalProperties: false
       |    },
       |    async execute(_toolCallId, params, _signal, _onUpdate, ctx) {
       |      const answer = await ctx.ui.input(params.question);
       |      const text = answer ?? "";
       |      return {
       |        content: [{ type: "text", text }],
       |        details: { answer: text }
       |      };
       |    }
       |  });
       |}
       |""".stripMargin
