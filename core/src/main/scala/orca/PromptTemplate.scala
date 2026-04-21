package orca

trait PromptTemplate:
  def autonomous(input: String, outputSchema: String, config: LlmConfig): String
  def interactive(
      input: String,
      outputSchema: String,
      config: LlmConfig
  ): String

  /** Builds a prompt asking the model to retry after a JSON parse failure.
    * Assumes it is sent as a follow-up turn on the same session — the original
    * output schema is expected to still be visible in prior context.
    */
  def retry(failedResponse: String, parseError: String): String
