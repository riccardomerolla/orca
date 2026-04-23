package orca

import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import sttp.tapir.Schema

import scala.deriving.Mirror

/** Bundles a tapir `Schema` and a jsoniter-scala `ConfiguredJsonValueCodec` for
  * a type. Flow scripts use `derives JsonData` on case classes that travel in
  * and out of LLM calls as structured JSON.
  *
  * The library's public API (e.g. `LlmTool.resultAs[O: JsonData]`,
  * `AgentInput`'s JSON given) bounds on `JsonData[O]` directly — no Schema or
  * codec implicits need to leak into user code. The top-level forwarder
  * `given`s below exist for a narrower reason: when tapir's `Schema.derived`
  * expands a nested case class inside `derives JsonData`, it looks for
  * `Schema[Child]` in lexical scope, and the forwarder bridges it from the
  * child's own `derives JsonData`. User scripts must import them with
  * `import orca.{*, given}` — Scala 3's plain wildcard imports exclude
  * givens.
  */
trait JsonData[A]:
  def schema: Schema[A]
  def codec: ConfiguredJsonValueCodec[A]

object JsonData:

  def apply[A](
      schemaInstance: Schema[A],
      codecInstance: ConfiguredJsonValueCodec[A]
  ): JsonData[A] =
    new JsonData[A]:
      val schema: Schema[A] = schemaInstance
      val codec: ConfiguredJsonValueCodec[A] = codecInstance

  inline def derived[A](using Mirror.Of[A]): JsonData[A] =
    apply(Schema.derived[A], ConfiguredJsonValueCodec.derived[A])

given schemaFromJsonData[A](using jd: JsonData[A]): Schema[A] = jd.schema

given codecFromJsonData[A](using jd: JsonData[A]): ConfiguredJsonValueCodec[A] =
  jd.codec
