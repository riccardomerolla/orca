import com.softwaremill.SbtSoftwareMillCommon.commonSmlBuildSettings
import com.softwaremill.Publish.ossPublishSettings
import Dependencies.*

ThisBuild / scalaVersion := V.scala

ThisBuild / scalacOptions ++= Seq(
  "-release",
  "21",
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Wunused:all",
  "-Wvalue-discard",
  "-Wnonunit-statement"
)

ThisBuild / javacOptions ++= Seq("--release", "21")

lazy val commonSettings = commonSmlBuildSettings ++ ossPublishSettings ++ Seq(
  organization := "org.virtuslab",
  versionScheme := Some("semver-spec"),
  libraryDependencies ++= Seq(munit),
  testFrameworks += new TestFramework("munit.Framework"),
  homepage := Some(url("https://github.com/VirtusLab/orca")),
  organizationHomepage := Some(url("https://virtuslab.com")),
  licenses := Seq(
    "Apache 2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")
  ),
  scmInfo := Some(
    ScmInfo(
      url("https://github.com/VirtusLab/orca"),
      "scm:git:git@github.com:VirtusLab/orca.git"
    )
  ),
  developers := List(
    Developer(
      id = "virtuslab",
      name = "VirtusLab",
      email = "info@virtuslab.com",
      url = url("https://virtuslab.com")
    )
  )
)

lazy val tools = (project in file("tools"))
  .settings(commonSettings)
  .settings(
    name := "orca-tools",
    libraryDependencies ++= Seq(
      osLib,
      jsoniter,
      jsoniterMacros,
      tapirApispec,
      sttpApispecCirce,
      ox,
      jsonSchemaValidator
    )
  )

lazy val claude = (project in file("claude"))
  .dependsOn(tools, tools % "test->test")
  .settings(commonSettings)
  .settings(
    name := "orca-claude",
    libraryDependencies ++= Seq(osLib, jsoniter, jsoniterMacros)
  )

lazy val codex = (project in file("codex"))
  .dependsOn(tools, tools % "test->test")
  .settings(commonSettings)
  .settings(
    name := "orca-codex",
    libraryDependencies ++= Seq(osLib, jsoniter, jsoniterMacros)
  )

lazy val flow = (project in file("flow"))
  .dependsOn(tools)
  .settings(commonSettings)
  .settings(
    name := "orca-flow",
    libraryDependencies ++= Seq(ox, jsoniterMacros, jsonSchemaValidator)
  )

lazy val runner = (project in file("runner"))
  .dependsOn(tools, flow, claude, codex)
  .settings(commonSettings)
  .settings(
    // Published as just "orca" so flow-script coordinates stay short.
    name := "orca",
    libraryDependencies ++= Seq(ox, mainargs, jline, fansi, jsoniterMacros)
  )

lazy val orcaRoot = (project in file("."))
  .settings(commonSettings)
  .settings(
    publish / skip := true,
    name := "orca-root"
  )
  .aggregate(tools, flow, claude, codex, runner)
