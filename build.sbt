import sbt.Test

val scala3Version    = "3.7.1"
val scalametaVersion = "4.8.2"
val laminarVersion   = "17.2.1"

Global / onChangedBuildSource := ReloadOnSourceChanges
ThisBuild / resolvers += "Sonatype OSS Snapshots" at "https://s01.oss.sonatype.org/content/repositories/snapshots"
ThisBuild / organization      := "org.jpablo"
ThisBuild / scalaVersion      := scala3Version
ThisBuild / semanticdbVersion := scalametaVersion
// sbt 2: bare settings are "common settings" injected into every subproject.
// (ThisBuild-scoping these would now double-append them alongside the
// project-level `scalacOptions ++=` below, and -Werror turns the resulting
// "flag set repeatedly" warnings into errors.)
scalacOptions ++= // Scala 3.x options
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

// P0 gate for the v2 redesign (docs/desktop-gx-v2-architecture.md D2): the
// native-image spike needs sharedJVM's runtime classpath as literal paths.
// sbt 2's `export` and `show` both emit ${OUT}/${CSR_CACHE} placeholders, whose
// expansions differ across the three CI runners. Retire together with
// spike/native-image once P0 is settled either way.
// Writes to a fixed path rather than returning one: sbt 2 refuses to cache a
// task whose result type is File/Path.
lazy val nativeImageClasspath =
  taskKey[Unit]("Write this project's runtime classpath to target/native-image-classpath.txt")

// The Scala.js linker output directory, written to a file for the vite plugin.
//
// vite-scalajs.js used to recover it by scanning sbt's STDOUT for a line that
// happened to be an existing directory. That works until sbt emits anything
// else around the task result, and then it fails with "sbt printed no existing
// directory" and a build that produced 0 modules.
//
// The trigger is a clock boundary, which is why it looked random: `viewer` uses
// dynver + BuildInfo, and dynver's version carries a MINUTE-resolution
// timestamp. An sbt invocation on the far side of a minute from the previous one
// sees a changed version, regenerates BuildInfo, recompiles a source — and the
// extra log traffic loses the task result line. CI passed at :43 and failed at
// :54 on the same tree; Netlify hit it too.
//
// sbt's stdout is not an API. This is.
lazy val scalaJSOutputDirFile =
  taskKey[Unit]("Write fullLinkJS's output directory to target/scalajs-output-dir.txt")

lazy val scalaJSFastOutputDirFile =
  taskKey[Unit]("Write fastLinkJS's output directory to target/scalajs-fast-output-dir.txt")

lazy val shared = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .enablePlugins(DynVerPlugin, BuildInfoPlugin)
  .in(file("shared"))
  // Compile: RecordTree (the record-label editing model) delegates to the
  // engine's RecordLabel parser — the single source of truth for the record
  // grammar. Tests additionally close the DOT text round-trip through the
  // pure-Scala graphviz engine (FeatureParitySpec). Acyclic: graphviz has no
  // project deps.
  .dependsOn(graphviz)
  .settings(
    name                     := "shared",
    Test / parallelExecution := false,
    buildInfoKeys            := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage         := "buildinfo",
    libraryDependencies ++= Seq(
      "com.lihaoyi"                %% "upickle"                        % "4.2.1",
      "com.lihaoyi"                %% "upickle-implicits-named-tuples" % "4.2.1",
      "com.lihaoyi"                %% "pprint"                         % "0.9.0",
      "com.lihaoyi"                %% "sourcecode"                     % "0.4.2",
      "com.softwaremill.quicklens" %% "quicklens"                      % "1.9.0",
      "org.scala-lang.modules"     %% "scala-parser-combinators"       % "2.4.0",
      "com.lihaoyi"                %% "fastparse"                      % "3.1.1",
      "org.scalameta"              %% "munit"                          % "1.0.0" % Test,
      "org.scalameta"              %% "munit-scalacheck"               % "1.0.0" % Test
    ),
    scalacOptions ++= Seq(
      "-explain",
      "-Ycheck-all-patmat",
      "-Yimports:java.lang,scala,scala.Predef,com.softwaremill.quicklens"
    ),
    testFrameworks := Seq(new TestFramework("munit.Framework"))
  ).jvmSettings(
    nativeImageClasspath := Def.uncached {
      // sbt 2 hands back xsbti.HashedVirtualFileRef, not File; fileConverter
      // is what turns those back into real paths on disk.
      val conv = fileConverter.value
      val out  = (ThisBuild / baseDirectory).value / "target" / "native-image-classpath.txt"
      val cp   = (Compile / fullClasspath).value.map(a => conv.toPath(a.data).toAbsolutePath.toString)
      IO.write(out, cp.mkString(java.io.File.pathSeparator))
      streams.value.log.info(s"wrote ${cp.length} classpath entries to $out")
    }
  ).jsSettings(
    // JS-specific settings
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
    libraryDependencies ++= Seq(
      "org.scala-js" %% "scalajs-dom" % "2.8.0"
    )
  )

// Pure-Scala port of the Graphviz `dot` engine (replaces @viz-js/viz at runtime).
// No project-specific or platform deps: usable from Scala.js AND the JVM.
// See PORT.md for plan/conformance tracking.
lazy val graphviz = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Full) // shared main is platform-neutral; JVM-only oracle harness lives in jvm/
  .in(file("graphviz"))
  .settings(
    name                     := "graphviz",
    Test / parallelExecution := false,
    libraryDependencies ++= Seq(
      "com.lihaoyi"   %% "fastparse"        % "3.1.1",
      "org.scalameta" %% "munit"            % "1.0.0" % Test,
      "org.scalameta" %% "munit-scalacheck" % "1.0.0" % Test,
      "com.lihaoyi"   %% "ujson"            % "4.2.1" % Test // M7 output gate
    ),
    // The fastparse front-end still trips pure-function inference. `-Xfatal-warnings`
    // came off with it (M0 tech-debt, PORT.md §6) and is back: the parser no longer
    // needs it, and dropping it module-wide had quietly exempted the whole layout
    // engine — where -Wsafe-init and exhaustivity warnings are worth the most, and
    // where five had accumulated unnoticed.
    scalacOptions --= Seq("-language:experimental.pureFunctions"),
    testFrameworks := Seq(new TestFramework("munit.Framework"))
  )
  .jsSettings(
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) }
  )

// The local-capabilities engine: policy, documents, watching, the library store
// and the audit log. See docs/desktop-gx-v2-architecture.md D2.2.
//
// CrossType.Full because the split is real, not incidental. `shared/` holds the
// model — content hashes, origin URIs, sync modes and the three-hash
// reconciliation — which the viewer needs and which touches no filesystem.
// `jvm/` holds everything that does I/O, and has no business being linked into a
// webview that D3 treats as an untrusted principal.
lazy val gxCore = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("gx-core"))
  .dependsOn(shared)
  .settings(
    name                     := "gx-core",
    Test / parallelExecution := false,
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit"            % "1.0.0" % Test,
      "org.scalameta" %% "munit-scalacheck" % "1.0.0" % Test
    ),
    testFrameworks := Seq(new TestFramework("munit.Framework"))
  )
  .jsSettings(
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) }
  )

// The `gx` command line, rewritten in Scala (D2). JVM-only: it is shipped as a
// GraalVM native-image binary, and P0 measured a parse-only command at 3.5-9.0ms
// cold across three platforms against the JVM jar's 512ms.
//
// Lives beside the Rust `gx/` rather than replacing it in place: the old binary
// is still referenced by two workflows, six smoke scripts and the Makefile, so
// retiring it is its own change once this one is proven.
lazy val gxCli =
  project
    .in(file("gx-cli"))
    .dependsOn(gxCore.jvm)
    .settings(
      name                     := "gx-cli",
      Test / parallelExecution := false,
      libraryDependencies ++= Seq(
        "org.scalameta" %% "munit" % "1.0.0" % Test
      ),
      testFrameworks := Seq(new TestFramework("munit.Framework")),
      nativeImageClasspath := Def.uncached {
        val conv = fileConverter.value
        val out  = (ThisBuild / baseDirectory).value / "target" / "gx-cli-classpath.txt"
        val cp   = (Compile / fullClasspath).value.map(a => conv.toPath(a.data).toAbsolutePath.toString)
        IO.write(out, cp.mkString(java.io.File.pathSeparator))
        streams.value.log.info(s"wrote ${cp.length} classpath entries to $out")
      }
    )

lazy val viewer =
  project
    .in(file("viewer"))
    .enablePlugins(ScalaJSPlugin, DynVerPlugin, BuildInfoPlugin)
    // gxCore.js is here for `gx-core/command` (D7.1): the command vocabulary has
    // to be the SAME vocabulary the UI executes, or the RPC path and the menus
    // become two implementations that drift. Only the shared half exists on JS —
    // gx-core has no JS sources of its own — so this links the pure model and
    // the commands, not the filesystem layer, which D3 keeps out of the webview.
    .dependsOn(shared.js, graphviz.js, gxCore.js) // M8: pure-Scala graphviz backend (flagged)
    .settings(
      name                            := "viewer",
      scalaJSUseMainModuleInitializer := true,
      // Both tasks still BUILD the output as a side effect, which is the reason
      // the vite plugin calls sbt at all: dropping the call would make a build
      // silently bundle whatever stale linker output was on disk.
      // Def.uncached: the task's whole purpose is the file it writes, and sbt 2
      // caches on inputs — so after a `target/` clean it would report success
      // with no file on disk, and the build would fail claiming sbt never wrote
      // it. Nothing here is expensive; the linking it depends on is cached on
      // its own terms.
      scalaJSOutputDirFile := Def.uncached {
        val out = (ThisBuild / baseDirectory).value / "target" / "scalajs-output-dir.txt"
        val dir = (Compile / fullLinkJSOutput).value
        IO.write(out, dir.getAbsolutePath)
        streams.value.log.info(s"wrote the Scala.js output directory to $out")
      },
      scalaJSFastOutputDirFile := Def.uncached {
        val out = (ThisBuild / baseDirectory).value / "target" / "scalajs-fast-output-dir.txt"
        val dir = (Compile / fastLinkJSOutput).value
        IO.write(out, dir.getAbsolutePath)
        streams.value.log.info(s"wrote the Scala.js output directory to $out")
      },
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
      // JS library facades are hand-written for the narrow surfaces the viewer
      // binds to: components/codeMirror/CodeMirrorFacade.scala and
      // backends/threejs/ThreeJS.scala. (ScalablyTyped's sbt-converter has no
      // sbt 2.x release, so the generated-facade plugin was dropped in the
      // sbt 2 migration.)
      libraryDependencies ++= Seq(
        "com.raquo" %% "laminar" % laminarVersion,
//        "com.raquo"                  %% "waypoint"    % "8.0.0",
        "com.softwaremill.quicklens"   %% "quicklens"   % "1.9.0",
        "com.softwaremill.macwire"     %% "macros"      % "2.6.4" % "provided",
        "io.laminext"                  %% "fetch"       % "0.17.0",
        "org.scala-js"                 %% "scalajs-dom" % "2.8.0",
        "com.lihaoyi"                  %% "upickle"     % "4.2.1",
        "com.lihaoyi"                  %% "pprint"      % "0.9.0",
        "com.softwaremill.magnolia1_3" %% "magnolia"    % "1.3.8",
//        "com.github.sbt"             %% "dynver"           % "5.1.0",
        "org.scalameta" %% "munit"            % "1.0.0" % Test,
        "org.scalameta" %% "munit-scalacheck" % "1.0.0" % Test
      ),
      excludeDependencies ++= Seq("org.scala-lang.modules" %% "scala-collection-compat"),
      // https://www.scala-js.org/doc/project/js-environments.html
      // Playwright runs tests in a real headless browser (full SVG/DOM support).
      // esbuild bundles the Scala.js test output to resolve npm bare imports.
      // (Def.uncached: sbt 2 caches task results by default and JSEnv / jsenv.Input
      // have no JsonFormat; these are also side-effecting by nature.)
      Test / jsEnv := Def.uncached(new jsenv.playwright.PWEnv(browserName = "chromium", headless = true, showLogs = true)),
      Test / jsEnvInput := Def.uncached {
        val prevInput = (Test / jsEnvInput).value
        val bundleDir = (Test / crossTarget).value / "test-bundled"
        bundleDir.mkdirs()
        prevInput.map {
          case org.scalajs.jsenv.Input.ESModule(path) =>
            val outFile = bundleDir / path.getFileName.toString
            import scala.sys.process._
            val cmd = Seq(
              "npx",
              "esbuild",
              path.toAbsolutePath.toString,
              "--bundle",
              "--format=esm",
              s"--outfile=${outFile.getAbsolutePath}"
            )
            val exitCode = Process(cmd, baseDirectory.value / "..").!
            if (exitCode != 0) sys.error(s"esbuild failed with exit code $exitCode")
            org.scalajs.jsenv.Input.ESModule(outFile.toPath)
          case other => other
        }
      }
//      testFrameworks += new TestFramework("munit.Framework")
    )

lazy val root =
  project
    .in(file("."))
    .aggregate(viewer, shared.js, shared.jvm, graphviz.js, graphviz.jvm, gxCore.js, gxCore.jvm, gxCli)
    .settings(
      name := "graph-explorer",
      welcomeMessage
      // The desktop/gx release build lives in
      // scripts/build-local-capabilities-release.sh, not here. It was an sbt
      // task (`buildLocalCapabilitiesRelease`) that could never complete: it
      // ran `npm run build` from inside the task, vite resolves
      // `scalajs:main.js` by shelling back into sbt, and that nested client
      // queued behind the task waiting for it. Any sbt task that shells out to
      // vite deadlocks the same way.
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
