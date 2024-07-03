import org.scalajs.linker.interface.ModuleSplitStyle

val typeExplorerVersion = "0.3.0"

val scala3Version = "3.4.1"
val scala2Version = "2.13.11"
val scalametaVersion = "4.8.2"
val zioPreludeVersion = "1.0.0-RC16"
val zioVersion = "2.1.1"
val laminarVersion = "17.0.0"

lazy val projectPath = settingKey[File]("projectPath")

Global / onChangedBuildSource := ReloadOnSourceChanges
Global / projectPath          := (ThisBuild / baseDirectory).value / ".type-explorer/meta"
ThisBuild / resolvers += "Sonatype OSS Snapshots" at "https://s01.oss.sonatype.org/content/repositories/snapshots"
ThisBuild / organization      := "org.jpablo"
ThisBuild / scalaVersion      := scala3Version
ThisBuild / version           := typeExplorerVersion
ThisBuild / semanticdbVersion := scalametaVersion
ThisBuild / scalacOptions ++= // Scala 3.x options
  Seq(
    "-Ykind-projector:underscores",
    "-Ysafe-init",
    "-language:implicitConversions",
    "-source:future",
    "-deprecation",
    "-Wunused:imports"
  )

lazy val protos =
  crossProject(JSPlatform, JVMPlatform)
    .crossType(CrossType.Full)
    .in(file("protos"))
    .settings(
      name         := "type-explorer-protos",
      scalaVersion := scala2Version,
      libraryDependencies ++= Seq(
        "org.scalameta" %%% "common" % scalametaVersion % "protobuf",
        "org.scalameta" %%% "common" % scalametaVersion
      ),
      scalacOptions --= Seq(
        "-Ykind-projector:underscores",
        "-Ysafe-init",
        "-source:future"
      ),
      scalacOptions ++= Seq("-Xsource:3"),
      Compile / PB.targets      := Seq(scalapb.gen(flatPackage = true) -> (Compile / sourceManaged).value / "scalapb"),
      Compile / PB.protoSources := Seq(file("protos/shared/src/main/protobuf")),
      Compile / semanticdbTargetRoot := projectPath.value
    )
    .jsSettings(
      scalaJSUseMainModuleInitializer := false
    )

val sharedSettings = Seq(
  libraryDependencies ++= Seq(
    "dev.zio"                    %%% "zio-prelude"       % zioPreludeVersion,
    "dev.zio"                    %%% "zio-json"          % "0.6.1",
    "dev.zio"                    %%% "zio-test"          % zioVersion % "test",
    "dev.zio"                    %%% "zio-test-sbt"      % zioVersion % "test",
    "dev.zio"                    %%% "zio-test-magnolia" % zioVersion % "test",
    "com.softwaremill.quicklens" %%% "quicklens"         % "1.9.0",
    "org.scalameta"              %%% "scalameta"         % scalametaVersion cross CrossVersion.for3Use2_13
  ),
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
)

/** The configuration {{{.crossType(CrossType.Pure).in(file("shared"))}}}
  *
  * enables three subprojects:
  *   - shared/.js (js stuff)
  *   - shared/.jvm (jvm stuff)
  *   - shared/src (for shared code)
  *
  * Check https://github.com/portable-scala/sbt-crossproject for more info
  */
lazy val shared =
  crossProject(JSPlatform, JVMPlatform)
    .crossType(CrossType.Pure)
    .in(file("shared"))
    .dependsOn(protos)
    .enablePlugins(BuildInfoPlugin)
    .settings(
      name             := "type-explorer-shared",
      buildInfoKeys    := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
      buildInfoPackage := "org.jpablo.typeexplorer",
      excludeDependencies ++= Seq(
        "org.scala-lang.modules" %% "scala-collection-compat",
        "org.scala-lang.modules" %% "scala-collection-compat_sjs1"
      ),
      Compile / semanticdbTargetRoot := projectPath.value
    )
    .settings(sharedSettings)
    .jsSettings(
      scalaJSUseMainModuleInitializer := false
    )

val mainBackendApp = "org.jpablo.typeexplorer.backend.webApp.WebApp"

lazy val backend =
  project
    .in(file("backend"))
    .dependsOn(shared.jvm, protos.jvm)
    // https://www.scala-sbt.org/sbt-native-packager/gettingstarted.html
    .enablePlugins(JavaAppPackaging)
    .settings(
      name                            := "type-explorer",
      reStart / mainClass             := Some(mainBackendApp),
      Compile / mainClass             := Some(mainBackendApp),
      Compile / discoveredMainClasses := Seq(),
      Universal / name                := "type-explorer",
      Universal / packageName         := s"${(Universal / name).value}-${version.value}",
      Universal / mappings +=
        file("scripts/type-explorer-compile-project.sh") -> "bin/type-explorer-compile-project.sh",
      libraryDependencies ++= Seq(
        "dev.zio"                 %% "zio-http"                 % "3.0.0-RC4",
        "dev.zio"                 %% "zio-logging"              % "2.2.2",
        "dev.zio"                 %% "zio-logging-slf4j"        % "2.1.13",
        "dev.zio"                 %% "zio-logging-slf4j-bridge" % "2.1.13",
        "org.json4s"              %% "json4s-native"            % "4.0.7",
        "guru.nidi"                % "graphviz-java"            % "0.18.1",
        "net.sourceforge.plantuml" % "plantuml"                 % "1.2023.9",
        "com.lihaoyi" %% "scalatags" % "0.11.1" cross CrossVersion.for3Use2_13 // Needed until org.scalameta-common upgrades to 3.x
      ),
      excludeDependencies ++= Seq(
        "com.thesamet.scalapb"   %% "scalapb-runtime",
        "org.scala-lang.modules" %% "scala-collection-compat"
      ),
      Compile / semanticdbTargetRoot := projectPath.value
    )
    .settings(sharedSettings)

val publicDev = taskKey[String]("output directory for `npm run dev`")
val publicProd = taskKey[String]("output directory for `npm run build`")

lazy val viewer =
  project
    .in(file("viewer"))
//    .dependsOn(shared.js)
    .enablePlugins(ScalaJSPlugin)
    .enablePlugins(BuildInfoPlugin)
    .settings(
      name                            := "viewer",
      scalaJSUseMainModuleInitializer := true,
      scalacOptions ++= Seq("-explain"),
      Compile / mainClass := Some("org.jpablo.typeexplorer.viewer.Viewer"),
      scalaJSLinkerConfig ~= {
        _.withModuleKind(ModuleKind.ESModule)
//          .withModuleSplitStyle(ModuleSplitStyle.SmallModulesFor(List("org.jpablo.typeexplorer.ui")))
          .withSourceMap(true)
      },
      externalNpm := {
        // scala.sys.process.Process(List("npm", "install", "--silent", "--no-audit", "--no-fund"), baseDirectory.value).!
        baseDirectory.value / ".."
      },
      libraryDependencies ++= Seq(
        "com.raquo"                             %%% "laminar"               % laminarVersion,
        "com.raquo"                             %%% "waypoint"              % "8.0.0",
        "com.softwaremill.quicklens"            %%% "quicklens"             % "1.9.0",
        "dev.zio"                               %%% "zio-json"              % "0.6.1",
        "dev.zio"                               %%% "zio-prelude"           % zioPreludeVersion,
        "io.laminext"                           %%% "fetch"                 % "0.17.0",
        "org.scala-js"                          %%% "scalajs-dom"           % "2.8.0",
        "com.github.plokhotnyuk.jsoniter-scala" %%% "jsoniter-scala-core"   % "2.30.3",
        "com.github.plokhotnyuk.jsoniter-scala" %%% "jsoniter-scala-macros" % "2.30.3", //% "compile-internal",
        "org.scalameta"                         %%% "munit"                 % "1.0.0"  % Test
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
      Compile / semanticdbTargetRoot := projectPath.value,
      testFrameworks += new TestFramework("munit.Framework")
    )

lazy val ui =
  project
    .in(file("ui"))
    .dependsOn(shared.js)
    .enablePlugins(ScalaJSPlugin)
//    .enablePlugins(ScalablyTypedConverterExternalNpmPlugin)
    .settings(
      scalaJSUseMainModuleInitializer := true,
      Compile / mainClass             := Some("org.jpablo.typeexplorer.ui.app.MainJS"),
      scalaJSLinkerConfig ~= {
        _.withModuleKind(ModuleKind.ESModule)
//          .withModuleSplitStyle(ModuleSplitStyle.SmallModulesFor(List("org.jpablo.typeexplorer.ui")))
          .withSourceMap(true)
      },
      externalNpm := {
        // scala.sys.process.Process(List("npm", "install", "--silent", "--no-audit", "--no-fund"), baseDirectory.value).!
        baseDirectory.value / ".."
      },
      // Compile / npmDependencies ++= Seq(
      //   "@types/bootstrap" -> "5.2.2"
      // ),
      libraryDependencies ++= Seq(
        "org.scala-js" %%% "scalajs-dom" % "2.2.0",
        "com.raquo"    %%% "laminar"     % laminarVersion,
        "io.laminext"  %%% "fetch"       % "0.17.0",
        "com.raquo"    %%% "waypoint"    % "8.0.0"
      ),
      excludeDependencies ++= Seq(
        "org.scala-lang.modules" %% "scala-collection-compat_sjs1"
      ),
//      publicDev                      := linkerOutputDirectory((Compile / fastLinkJS).value).getAbsolutePath,
      publicProd                     := linkerOutputDirectory((Compile / fullLinkJS).value).getAbsolutePath,
      Compile / semanticdbTargetRoot := projectPath.value
    )
    .settings(sharedSettings)

def linkerOutputDirectory(v: Attributed[org.scalajs.linker.interface.Report]): File =
  v.get(scalaJSLinkerOutputDirectory.key).getOrElse {
    throw new MessageOnlyException(
      "Linking report was not attributed with output directory. Please report this as a Scala.js bug."
    )
  }

lazy val root =
  project
    .in(file("."))
    .aggregate(protos.jvm, backend, ui, viewer, shared.js, shared.jvm)
    .settings(
      name := "type-explorer",
      welcomeMessage
    )

def welcomeMessage = onLoadMessage := {
  import scala.Console
  def header(text:  String): String = s"${Console.RED}$text${Console.RESET}"
  def item(text:    String): String = s"${Console.GREEN}> ${Console.CYAN}$text${Console.RESET}"
  def subItem(text: String): String = s"  ${Console.YELLOW}> ${Console.CYAN}$text${Console.RESET}"

  s"""|${header(s"Type Explorer ${version.value}")}
      |
      |Useful sbt tasks:
      |${item("~ backend/reStart")} - start backend server
      |${item("~ ui/fastLinkJS")} - compile ui
      """.stripMargin
}
