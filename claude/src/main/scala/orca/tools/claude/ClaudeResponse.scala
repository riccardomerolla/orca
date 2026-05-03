package orca.tools.claude

import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import orca.tools.claude.streamjson.RawJson
import orca.{Backend, LlmResult, SessionId, Usage}

/** Subset of the JSON Claude Code emits when invoked with `--output-format
  * json`. Field names mirror the CLI output.
  */
case class ClaudeHeadlessResponse(
    session_id: String,
    result: String,
    usage: ClaudeUsage,
    total_cost_usd: Option[BigDecimal] = None,
    is_error: Option[Boolean] = None,
    /** Claude reports the resolved model name as the KEY of `modelUsage` —
      * `{"claude-sonnet-4-6": {...usage...}}`. There's no top-level `model`
      * field on the headless result, so this is where we read it from. In
      * practice exactly one entry per turn; the first key wins.
      */
    modelUsage: Option[Map[String, RawJson]] = None
) derives ConfiguredJsonValueCodec:

  def toLlmResult: LlmResult[Backend.ClaudeCode.type] =
    LlmResult(
      sessionId = SessionId[Backend.ClaudeCode.type](session_id),
      output = result,
      usage = Usage(
        inputTokens = usage.input_tokens,
        outputTokens = usage.output_tokens,
        cost = total_cost_usd
      ),
      model = modelUsage.flatMap(_.keys.headOption)
    )

case class ClaudeUsage(
    input_tokens: Long,
    output_tokens: Long
) derives ConfiguredJsonValueCodec
