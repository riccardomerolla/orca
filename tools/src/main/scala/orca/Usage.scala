package orca

case class Usage(
    inputTokens: Long,
    outputTokens: Long,
    cost: Option[BigDecimal]
):
  /** Combine two usages; cost is `Some` iff at least one side reports it. */
  def +(that: Usage): Usage =
    Usage(
      inputTokens = inputTokens + that.inputTokens,
      outputTokens = outputTokens + that.outputTokens,
      cost = (cost ++ that.cost).reduceOption(_ + _)
    )

object Usage:
  val empty: Usage = Usage(0L, 0L, None)
