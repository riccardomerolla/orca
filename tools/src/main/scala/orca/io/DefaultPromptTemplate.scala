package orca.io

import orca.{LlmConfig, PromptTemplate}

/** The `PromptTemplate` Orca uses unless `flow(..., promptTemplate = ...)`
  * overrides it. Each method builds the final prompt string via Scala
  * string interpolation.
  *
  * Autonomous calls ship the JSON Schema inline in the prompt — they
  * route through `claude -p --output-format json` with no structured
  * validation on the CLI side. Interactive calls rely on `--json-schema`
  * for enforcement and let the agent reply in natural conversation until
  * it has the final structured value; the schema is still summarised in
  * the prompt so the model knows the target shape, but no magic marker
  * is required.
  */
object DefaultPromptTemplate extends PromptTemplate:

  private val RawJsonRules: String =
    """- no surrounding prose or commentary
      |- no markdown code fences
      |- raw JSON only""".stripMargin

  def autonomous(
      input: String,
      outputSchema: String,
      config: LlmConfig
  ): String =
    s"""Complete the task described in the input. Respond with a JSON value that
       |conforms to the output schema below. Rules:
       |$RawJsonRules
       |
       |Input:
       |$input
       |
       |Output schema:
       |$outputSchema
       |""".stripMargin

  def interactive(
      input: String,
      outputSchema: String,
      config: LlmConfig
  ): String =
    s"""Collaborate with the user on the task described in the input. Respond
       |normally during the conversation. When the task is complete, return a
       |final value that matches the output schema below — the runtime will
       |validate the structured result automatically, so reply in plain prose
       |without code fences.
       |
       |Input:
       |$input
       |
       |Output schema:
       |$outputSchema
       |""".stripMargin

  def retry(failedResponse: String, parseError: String): String =
    s"""Your previous response could not be parsed as a JSON value matching the
       |required schema.
       |
       |Parser error:
       |$parseError
       |
       |Previous response:
       |--- BEGIN RESPONSE ---
       |$failedResponse
       |--- END RESPONSE ---
       |
       |Please produce a valid JSON value matching the schema from the original
       |instructions. Rules:
       |$RawJsonRules
       |""".stripMargin
