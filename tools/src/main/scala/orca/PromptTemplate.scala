package orca

/** Builds the literal prompt strings Orca sends to the LLM for each
  * invocation mode. Each method takes the serialized user input, the
  * generated JSON Schema for the expected output, and the active
  * `LlmConfig`, and returns the final prompt text. Swap the default
  * (`DefaultPromptTemplate`) by passing `promptTemplate = ...` to
  * `orca(...)` when you want to customise phrasing, add guardrails, or
  * use a different structured-output convention.
  */
trait PromptTemplate:
  /** Prompt for a non-interactive call: the model is expected to emit the
    * structured JSON response directly, with no user turn in between.
    */
  def autonomous(input: String, outputSchema: String, config: LlmConfig): String

  /** Prompt for an interactive call: the model converses with the user and
    * emits the structured JSON response only after the `<<<ORCA_DONE>>>`
    * marker.
    */
  def interactive(
      input: String,
      outputSchema: String,
      config: LlmConfig
  ): String

  /** Builds a prompt asking the model to retry after a JSON parse failure.
    * Assumes it is sent as a follow-up turn on the same session — the
    * original output schema is expected to still be visible in prior
    * context.
    */
  def retry(failedResponse: String, parseError: String): String
