package orca

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec

// TODO: missing docs on what this is
trait AgentInput[A]:
  def serialize(a: A): String

object AgentInput:
  given AgentInput[String] with
    def serialize(a: String): String = a

  given [A](using codec: ConfiguredJsonValueCodec[A]): AgentInput[A] with
    def serialize(a: A): String = writeToString(a)
