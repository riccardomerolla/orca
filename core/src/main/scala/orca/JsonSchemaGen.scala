package orca

import io.circe.syntax.*
import sttp.apispec.circe.*
import sttp.tapir.Schema
import sttp.tapir.docs.apispec.schema.TapirSchemaToJsonSchema

object JsonSchemaGen:
  def apply[O](using schema: Schema[O]): String =
    val jsonSchema =
      TapirSchemaToJsonSchema(schema, markOptionsAsNullable = true)
    jsonSchema.asJson.noSpaces
