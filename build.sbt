import org.scalajs.linker.interface.ModuleSplitStyle

val graphExplorerVersion = "0.3.0"

val scala3Version = "3.5.0"
val scalametaVersion = "4.8.2"
val zioPreludeVersion = "1.0.0-RC16"
val zioVersion = "2.1.1"
val laminarVersion = "17.1.0"

Global / onChangedBuildSource := ReloadOnSourceChanges
ThisBuild / resolvers += "Sonatype OSS Snapshots" at "https://s01.oss.sonatype.org/content/repositories/snapshots"
ThisBuild / organization      := "org.jpablo"
ThisBuild / scalaVersion      := scala3Version
ThisBuild / version           := graphExplorerVersion
ThisBuild / semanticdbVersion := scalametaVersion
ThisBuild / scalacOptions ++= // Scala 3.x options
  Seq(
//    "-Ykind-projector:underscores",
    "-Wsafe-init",
    "-language:implicitConversions",
    "-language:experimental.namedTuples",
    "-source:future",
    "-deprecation",
    "-Wunused:imports",
    "-Xfatal-warnings"
  )

val publicDev = taskKey[String]("output directory for `npm run dev`")
val publicProd = taskKey[String]("output directory for `npm run build`")

lazy val viewer =
  project
    .in(file("viewer"))
    .enablePlugins(ScalaJSPlugin)
    .enablePlugins(BuildInfoPlugin)
    .settings(
      name                            := "viewer",
      scalaJSUseMainModuleInitializer := true,
      scalacOptions ++= Seq("-explain", "-Ycheck-all-patmat"),
      Compile / mainClass := Some("org.jpablo.graphexplorer.viewer.Viewer"),
      scalaJSLinkerConfig ~= {
        _.withModuleKind(ModuleKind.ESModule)
          .withSourceMap(true)
      },
      externalNpm := {
        // scala.sys.process.Process(List("npm", "install", "--silent", "--no-audit", "--no-fund"), baseDirectory.value).!
        baseDirectory.value / ".."
      },
      libraryDependencies ++= Seq(
        "com.raquo"                  %%% "laminar"          % laminarVersion,
        "com.raquo"                  %%% "waypoint"         % "8.0.0",
        "com.softwaremill.quicklens" %%% "quicklens"        % "1.9.0",
        "io.laminext"                %%% "fetch"            % "0.17.0",
        "org.scala-js"               %%% "scalajs-dom"      % "2.8.0",
        "com.lihaoyi"                %%% "upickle"          % "4.0.0",
        "org.scalameta"              %%% "munit"            % "1.0.0"  % Test,
        "org.scalameta"              %%% "munit-scalacheck" % "1.0.0" % Test
      ),
      excludeDependencies ++= Seq(
        "org.scala-lang.modules" %% "scala-collection-compat_sjs1"
      ),
//      jsEnv                          := new org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv(),
      Test / jsEnv := new jsenv.playwright.PWEnv(
        browserName = "chrome",
        headless    = true,
        showLogs    = true
      ),
      publicDev                      := linkerOutputDirectory((Compile / fastLinkJS).value).getAbsolutePath,
      publicProd                     := linkerOutputDirectory((Compile / fullLinkJS).value).getAbsolutePath,
      testFrameworks += new TestFramework("munit.Framework")
    )

def linkerOutputDirectory(v: Attributed[org.scalajs.linker.interface.Report]): File =
  v.get(scalaJSLinkerOutputDirectory.key).getOrElse {
    throw new MessageOnlyException(
      "Linking report was not attributed with output directory. Please report this as a Scala.js bug."
    )
  }

lazy val root =
  project
    .in(file("."))
    .aggregate(viewer)
    .settings(
      name := "graph-explorer",
      welcomeMessage
    )

def welcomeMessage = onLoadMessage := {
  import scala.Console
  def header(text:  String): String = s"${Console.RED}$text${Console.RESET}"
  def item(text:    String): String = s"${Console.GREEN}> ${Console.CYAN}$text${Console.RESET}"

  s"""|${header(s"Graph Explorer ${version.value}")}
      |
      |Useful sbt tasks:
      |${item("~ viewer/fastLinkJS")} - compile ui
      """.stripMargin
}
