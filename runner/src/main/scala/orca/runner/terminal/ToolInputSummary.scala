package orca.runner.terminal

import scala.annotation.tailrec

/** Produces a short, human-readable summary of a tool call's raw JSON
  * input — the bit the renderer shows in parentheses after the tool
  * name. The implementation is a deliberately small hand-written JSON
  * string extractor: a full parser would cost a dependency edge and
  * time for what is purely a display heuristic. If the input doesn't
  * match one of the known "headline" fields, we fall back to the
  * truncated JSON so nothing is lost.
  */
private[terminal] object ToolInputSummary:

  /** Ordered field names tried against the input's top-level JSON
    * object; the first match wins. Order matters — `file_path` beats
    * `path`, which beats the more generic `pattern`/`query`.
    */
  private val HeadlineFields: List[String] =
    List("file_path", "path", "command", "pattern", "query", "url", "description")

  /** Returns an already-truncated headline suitable for rendering
    * after the tool name. Empty string means "no args to show".
    */
  def summarise(rawJson: String, maxLength: Int): String =
    val collapsed = collapseWhitespace(rawJson)
    if collapsed.isEmpty || collapsed == "{}" then ""
    else
      HeadlineFields.iterator
        .flatMap(extractStringField(collapsed, _))
        .nextOption() match
        case Some(value) => s"(${truncate(value, maxLength)})"
        case None        => truncate(collapsed, maxLength)

  private def collapseWhitespace(raw: String): String =
    raw.replaceAll("\\s+", " ").trim

  private def truncate(s: String, maxLength: Int): String =
    if s.length <= maxLength then s else s"${s.take(maxLength)}…"

  /** Matches a `"field":"value"` entry and walks the value honouring
    * `\"` / `\\` escapes. Returns `None` if the field isn't present
    * or the string doesn't terminate. Deliberately not a full JSON
    * parser — escapes beyond the common shell/path ones round-trip
    * verbatim because they wouldn't otherwise appear in tool inputs.
    */
  private def extractStringField(json: String, field: String): Option[String] =
    val needle = s""""$field":""""
    val start = json.indexOf(needle)
    if start < 0 then None
    else
      val valueStart = start + needle.length
      findStringEnd(json, valueStart).map: end =>
        unescape(json.substring(valueStart, end))

  @tailrec
  private def findStringEnd(s: String, i: Int): Option[Int] =
    if i >= s.length then None
    else
      val ch = s.charAt(i)
      if ch == '\\' then findStringEnd(s, i + 2)
      else if ch == '"' then Some(i)
      else findStringEnd(s, i + 1)

  private def unescape(s: String): String =
    val sb = new StringBuilder(s.length)
    var i = 0
    while i < s.length do
      val ch = s.charAt(i)
      if ch == '\\' && i + 1 < s.length then
        sb.append(replacement(s.charAt(i + 1)))
        i += 2
      else
        sb.append(ch)
        i += 1
    sb.toString

  private def replacement(escaped: Char): Char = escaped match
    case '"'  => '"'
    case '\\' => '\\'
    case '/'  => '/'
    case 'n'  => '\n'
    case 't'  => '\t'
    case 'r'  => '\r'
    case other => other
