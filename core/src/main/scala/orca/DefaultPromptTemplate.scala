package orca

object DefaultPromptTemplate extends PromptTemplate:

  val DoneMarker: String = "<<<ORCA_DONE>>>"

  def autonomous(
      input: String,
      outputSchema: String,
      config: LlmConfig
  ): String =
    s"""Complete the task described in the input. Respond with a JSON value that
       |conforms to the output schema below. Rules:
       |- no surrounding prose or commentary
       |- no markdown code fences
       |- raw JSON only
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
       |normally during the conversation. When the task is complete and you are
       |ready to hand back a final structured result:
       |- emit the literal marker $DoneMarker on its own line
       |- follow it with a single JSON value conforming to the output schema
       |- do not emit the marker before the task is complete
       |- do not wrap the JSON in markdown code fences
       |
       |Input:
       |$input
       |
       |Output schema:
       |$outputSchema
       |""".stripMargin
