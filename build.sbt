import org.scalajs.linker.interface.ModuleSplitStyle
import sbt.Test

val scala3Version    = "3.7.1"
val scalametaVersion = "4.8.2"
val laminarVersion   = "17.2.1"
lazy val buildLocalCapabilitiesRelease = taskKey[Unit]("Build release binaries for graph-explorer-desktop and gx")

Global / onChangedBuildSource := ReloadOnSourceChanges
ThisBuild / resolvers += "Sonatype OSS Snapshots" at "https://s01.oss.sonatype.org/content/repositories/snapshots"
ThisBuild / organization      := "org.jpablo"
ThisBuild / scalaVersion      := scala3Version
ThisBuild / semanticdbVersion := scalametaVersion
ThisBuild / scalacOptions ++= // Scala 3.x options
  Seq(
    "-Wsafe-init",
    "-language:implicitConversions",
    "-language:experimental.pureFunctions",
    "-language:strictEquality",
//    "-language:experimental.captureChecking",
    "-source:future",
    "-deprecation",
    "-Wunused:imports",
    "-Xfatal-warnings",
    "-preview"
  )

lazy val shared = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .enablePlugins(DynVerPlugin, BuildInfoPlugin)
  .in(file("shared"))
  .settings(
    name                     := "shared",
    Test / parallelExecution := false,
    buildInfoKeys            := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage         := "buildinfo",
    libraryDependencies ++= Seq(
      "com.lihaoyi"                %%% "upickle"                        % "4.2.1",
      "com.lihaoyi"                %%% "upickle-implicits-named-tuples" % "4.2.1",
      "com.lihaoyi"                %%% "pprint"                         % "0.9.0",
      "com.lihaoyi"                %%% "sourcecode"                     % "0.4.2",
      "com.softwaremill.quicklens" %%% "quicklens"                      % "1.9.0",
      "org.scala-lang.modules"     %%% "scala-parser-combinators"       % "2.4.0",
      "com.lihaoyi"                %%% "fastparse"                      % "3.1.1",
      "org.scalameta"              %%% "munit"                          % "1.0.0" % Test,
      "org.scalameta"              %%% "munit-scalacheck"               % "1.0.0" % Test
    ),
    scalacOptions ++= Seq(
      "-explain",
      "-Ycheck-all-patmat",
      "-Yimports:java.lang,scala,scala.Predef,com.softwaremill.quicklens"
    ),
    testFrameworks := Seq(new TestFramework("munit.Framework"))
  ).jsSettings(
    // JS-specific settings
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
    libraryDependencies ++= Seq(
      "org.scala-js" %%% "scalajs-dom" % "2.8.0"
    )
  )

lazy val viewer =
  project
    .in(file("viewer"))
    .enablePlugins(ScalaJSPlugin, DynVerPlugin, BuildInfoPlugin)
    .dependsOn(shared.js)
    .enablePlugins(ScalablyTypedConverterExternalNpmPlugin)
    .settings(
      name                            := "viewer",
      scalaJSUseMainModuleInitializer := true,
      buildInfoOptions ++= Seq(BuildInfoOption.BuildTime, BuildInfoOption.ToMap),
      scalacOptions ++= Seq(
        "-explain",
        "-Ycheck-all-patmat",
        "-Yimports:java.lang,scala,scala.Predef,org.scalajs,com.softwaremill.quicklens"
      ),
      Compile / mainClass      := Some("org.jpablo.graphexplorer.viewer.Viewer"),
      Test / parallelExecution := false,
      scalaJSLinkerConfig ~= {
        _.withModuleKind(ModuleKind.ESModule)
//          .withOutputPatterns(OutputPatterns.fromJSFile("%s.mjs"))
          .withSourceMap(true)
      },
      // Point ScalablyTyped at the already-installed node_modules. Do NOT run
      // `npm install` here: every viewer sbt task evaluates this, and when the
      // build is driven by `npm run build` -> @scala-js/vite-plugin-scalajs ->
      // sbt, a nested `npm install` rewrites node_modules out from under the
      // running vite process. Dev/CI install deps explicitly before building.
      externalNpm := baseDirectory.value / "..",
      // Only generate facades for the libraries the viewer actually binds to.
      // The rest of package.json `dependencies` are JS-only / build-only / a
      // local `file:` dep with no TypeScript types, so ignore them.
      stIgnore ++= List(
        "node",
        "dot-parser",
        "@scala-js/vite-plugin-scalajs",
        "mermaid",
        "uuid"
      ),
      libraryDependencies ++= Seq(
        "com.raquo" %%% "laminar" % laminarVersion,
//        "com.raquo"                  %%% "waypoint"    % "8.0.0",
        "com.softwaremill.quicklens"   %%% "quicklens"   % "1.9.0",
        "com.softwaremill.macwire"     %%% "macros"      % "2.6.4" % "provided",
        "io.laminext"                  %%% "fetch"       % "0.17.0",
        "org.scala-js"                 %%% "scalajs-dom" % "2.8.0",
        "com.lihaoyi"                  %%% "upickle"     % "4.2.1",
        "com.lihaoyi"                  %%% "pprint"      % "0.9.0",
        "com.softwaremill.magnolia1_3" %%% "magnolia"    % "1.3.8",
//        "com.github.sbt"             %%% "dynver"           % "5.1.0",
        "com.microsoft.playwright" % "playwright"       % "1.50.0" % Test,
        "com.microsoft.playwright" % "driver-bundle"    % "1.50.0" % Test,
        "org.seleniumhq.selenium"  % "selenium-java"    % "4.29.0" % Test,
        "org.scalameta"          %%% "munit"            % "1.0.0"  % Test,
        "org.scalameta"          %%% "munit-scalacheck" % "1.0.0"  % Test
        // ScalablyTyped facades for codemirror/jsdom/viz-js are generated at
        // build time by ScalablyTypedConverterExternalNpmPlugin (enabled above)
        // from node_modules + the project's pinned sbt-converter. No pinned
        // content hashes -- local and CI each generate against the project's
        // own Scala.js, so they are reproducible by construction.
      ),
      excludeDependencies ++= Seq("org.scala-lang.modules" %% "scala-collection-compat_sjs1"),
      // https://www.scala-js.org/doc/project/js-environments.html
      // Playwright runs tests in a real headless browser (full SVG/DOM support).
      // esbuild bundles the Scala.js test output to resolve npm bare imports.
      Test / jsEnv := new jsenv.playwright.PWEnv(browserName = "chromium", headless = true, showLogs = true),
      Test / jsEnvInput := {
        val prevInput = (Test / jsEnvInput).value
        val bundleDir = (Test / crossTarget).value / "test-bundled"
        bundleDir.mkdirs()
        prevInput.map {
          case org.scalajs.jsenv.Input.ESModule(path) =>
            val outFile = bundleDir / path.getFileName.toString
            import scala.sys.process._
            val cmd = Seq(
              "npx", "esbuild", path.toAbsolutePath.toString,
              "--bundle", "--format=esm",
              s"--outfile=${outFile.getAbsolutePath}"
            )
            val exitCode = Process(cmd, baseDirectory.value / "..").!
            if (exitCode != 0) sys.error(s"esbuild failed with exit code $exitCode")
            org.scalajs.jsenv.Input.ESModule(outFile.toPath)
          case other => other
        }
      },
//      testFrameworks += new TestFramework("munit.Framework")
    )

lazy val root =
  project
    .in(file("."))
    .aggregate(viewer, shared.js, shared.jvm)
    .settings(
      name := "graph-explorer",
      welcomeMessage,
      buildLocalCapabilitiesRelease := {
        val log  = streams.value.log
        val base = baseDirectory.value

        def run(cmd: Seq[String], cwd: java.io.File): Unit = {
          log.info(s"Running `${cmd.mkString(" ")}` in ${cwd.getPath}")
          val exitCode = scala.sys.process.Process(cmd, cwd).!
          if (exitCode != 0) sys.error(s"Command failed (${exitCode}): ${cmd.mkString(" ")}")
        }

        // Tauri embeds `frontendDist` (../../dist) into the desktop binary at compile time.
        // `cargo build` only recompiles when a Rust source changes, so a frontend-only
        // change to dist/ is a cargo no-op and the binary silently keeps a stale embedded
        // bundle. Force a recompile by bumping the mtime of the desktop entrypoint so
        // generate_context! re-reads the freshly built dist/.
        def forceRecompile(entrypoint: java.io.File): Unit = {
          if (!entrypoint.setLastModified(System.currentTimeMillis()))
            sys.error(s"Could not touch ${entrypoint.getPath} to force a desktop recompile")
        }

        // The desktop binary MUST be built with tauri's `custom-protocol` feature.
        // tauri's build script sets `dev = !custom-protocol`; without the feature a
        // release `cargo build` is still a *dev* build that loads `devUrl`
        // (http://localhost:5173) instead of the embedded frontendDist, so the
        // window is blank unless a vite dev server happens to be running. The
        // Tauri CLI adds this feature automatically; a bare `cargo build` does not.
        run(Seq("npm", "run", "build"), base)
        forceRecompile(base / "desktop" / "src-tauri" / "src" / "main.rs")
        run(
          Seq("cargo", "build", "--release", "--locked", "--features", "tauri/custom-protocol"),
          base / "desktop" / "src-tauri"
        )
        run(Seq("cargo", "build", "--release", "--locked"), base / "gx")
      }
    )

def welcomeMessage = onLoadMessage := {
  import scala.Console
  def header(text: String): String = s"${Console.RED}$text${Console.RESET}"

  def item(text: String): String = s"${Console.GREEN}> ${Console.CYAN}$text${Console.RESET}"

  s"""|${header(s"Graph Explorer ${version.value}")}
      |
      |Useful sbt tasks:
      |${item("~ viewer/fastLinkJS")} - compile ui
      """.stripMargin
}
