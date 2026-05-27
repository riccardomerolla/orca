package orca.util

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReader,
  JsonValueCodec,
  JsonWriter
}

/** Opaque wrapper for "a JSON subtree, held in its serialized form". Used for
  * message fields whose shape varies too much to be worth modelling — tool
  * inputs, MCP tool arguments, structured-output payloads, MCP tool results.
  * The codec reads and writes the raw bytes straight through without
  * re-parsing.
  *
  * `nullValue` is the literal four-character string `"null"` — i.e., a missing
  * field decodes indistinguishably from an actual JSON `null`. Fields that need
  * to detect absence must wrap `RawJson` in `Option`.
  */
private[orca] opaque type RawJson = String

private[orca] object RawJson:
  def apply(s: String): RawJson = s

  extension (r: RawJson) def value: String = r

  given JsonValueCodec[RawJson] with
    def decodeValue(in: JsonReader, default: RawJson): RawJson =
      // `readRawValAsBytes` returns the next JSON value verbatim, including
      // whitespace around nested structures; we re-interpret as UTF-8.
      new String(
        in.readRawValAsBytes(),
        java.nio.charset.StandardCharsets.UTF_8
      )

    def encodeValue(x: RawJson, out: JsonWriter): Unit =
      // Writing from the String directly avoids an intermediate
      // `getBytes` allocation — jsoniter's writer converts once.
      out.writeRawVal(x.value.getBytes(java.nio.charset.StandardCharsets.UTF_8))

    def nullValue: RawJson = "null"
