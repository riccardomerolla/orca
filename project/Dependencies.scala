import sbt.*

object Dependencies {
  object V {
    val scala          = "3.3.6"
    val osLib          = "0.11.4"
    val jsoniter       = "2.33.3"
    val tapir          = "1.11.40"
    val sttpApispec    = "0.11.10"
    val ox             = "0.6.1"
    val sttpClient     = "4.0.9"
    val mainargs       = "0.7.6"
    val jline          = "3.28.0"
    val fansi          = "0.5.0"
    val scribe         = "3.15.3"
    val munit          = "1.1.1"
    val jsonSchemaValidator = "1.5.4"
  }

  val osLib      = "com.lihaoyi"                          %% "os-lib"             % V.osLib
  val jsoniter   = "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % V.jsoniter
  val jsoniterMacros = "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % V.jsoniter % "provided, test"

  val tapirApispec = "com.softwaremill.sttp.tapir"   %% "tapir-apispec-docs" % V.tapir
  val sttpApispecCirce = "com.softwaremill.sttp.apispec" %% "jsonschema-circe" % V.sttpApispec

  val ox         = "com.softwaremill.ox"           %% "core"                  % V.ox
  val sttpClient = "com.softwaremill.sttp.client4" %% "core"                  % V.sttpClient
  val sttpClientOx = "com.softwaremill.sttp.client4" %% "ox"                  % V.sttpClient

  val mainargs   = "com.lihaoyi"                   %% "mainargs"              % V.mainargs
  val jline      = "org.jline"                      % "jline"                 % V.jline
  val fansi      = "com.lihaoyi"                   %% "fansi"                 % V.fansi
  val scribe     = "com.outr"                      %% "scribe"                % V.scribe

  val munit      = "org.scalameta"                 %% "munit"                 % V.munit % Test
  val jsonSchemaValidator = "com.networknt" % "json-schema-validator" % V.jsonSchemaValidator % Test
}
