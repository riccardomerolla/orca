package orca

case class Usage(
    inputTokens: Long,
    outputTokens: Long,
    cost: Option[BigDecimal]
)

object Usage:
  val empty: Usage = Usage(0L, 0L, None)
