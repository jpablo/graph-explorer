package org.jpablo.graphexplorer.gxcore.fs

import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/** `$GX_HOME` has to mean the same directory to `gx` and to the desktop, or it
  * is worse than not existing: a library in one place and the runtime file that
  * names its socket in another is a split nobody would think to look for.
  *
  * The desktop's half of this rule lives in main.rs
  * (`graph_explorer_dir_from`), tested there. These are the same four cases.
  */
class GxHomeSpec extends FunSuite:

  private def home(path: String): () => Path = () => Paths.get(path)
  private def env(pairs: (String, String)*): String => Option[String] =
    val map = pairs.toMap
    key => map.get(key)

  test("with no GX_HOME it is ~/.graph-explorer") {
    assertEquals(
      GxHome.resolve(env(), home("/Users/someone")).toOption.get,
      Paths.get("/Users/someone/.graph-explorer")
    )
  }

  test("GX_HOME replaces the directory, it does not nest inside it") {
    // The variable names the data directory itself. Resolving it to
    // `$GX_HOME/.graph-explorer` would put a hidden directory inside a path the
    // caller chose explicitly, which is the opposite of what naming one is for.
    assertEquals(
      GxHome.resolve(env("GX_HOME" -> "/tmp/scratch"), home("/Users/someone")).toOption.get,
      Paths.get("/tmp/scratch")
    )
  }

  test("a relative GX_HOME is REFUSED, not absolutised") {
    // It used to be absolutised. That made each process internally consistent
    // and the two of them inconsistent with each other: absolutising resolves
    // against the reading process's working directory, and `gx` runs from the
    // user's shell while a GUI-launched desktop runs from wherever the launcher
    // put it. The two halves would use different libraries and neither would
    // say so — the exact failure GX_HOME exists to prevent.
    //
    // The desktop refuses the same values, in graph_explorer_dir_from.
    for relative <- List("scratch", "./scratch", "../scratch", "a/b") do
      GxHome.resolve(env("GX_HOME" -> relative), home("/Users/someone")) match
        case Right(path) => fail(s"[$relative] should have been refused, got $path")
        case Left(why) =>
          assert(why.contains("absolute"), s"[$relative] the reason should name the rule: $why")
          assert(why.contains(relative), s"[$relative] the reason should quote the value: $why")
  }

  test("an absolute GX_HOME is normalised, so both halves compare equal") {
    // `/tmp/../tmp/scratch` and `/tmp/scratch` are one directory. The library
    // store compares paths to decide whether a name escapes its directory, so
    // the two spellings must fold to one — on both sides of the product.
    assertEquals(
      GxHome.resolve(env("GX_HOME" -> "/tmp/../tmp/scratch"), home("/Users/someone")),
      Right(Paths.get("/tmp/scratch"))
    )
  }

  test("a blank GX_HOME means unset, not the filesystem root") {
    // `export GX_HOME="$SOMETHING"` with SOMETHING unset is how a shell script
    // passes through an absent value. Taking it literally would put the library
    // at `/library`.
    for blank <- List("", "   ") do
      assertEquals(
        GxHome.resolve(env("GX_HOME" -> blank), home("/Users/someone")).toOption.get,
        Paths.get("/Users/someone/.graph-explorer"),
        s"blank value [$blank] should read as unset"
      )
  }

  test("library and runtime hang off the same root") {
    // They are only useful as a set: the runtime file names the socket for the
    // library it belongs to, so a half-applied override splits them silently.
    val root = GxHome.resolve(env("GX_HOME" -> "/tmp/scratch"), home("/Users/someone")).toOption.get
    assertEquals(GxHome.libraryDir(root), Paths.get("/tmp/scratch/library"))
    assertEquals(GxHome.runtimeDir(root), Paths.get("/tmp/scratch/runtime"))
  }

  // ------------------------------------------------ the folder of new diagrams

  test("with no GX_DIAGRAMS_DIR, new diagrams go to ~/Documents/Graph Explorer") {
    assertEquals(
      GxHome.diagramsDir(env(), home("/Users/someone")),
      Right(Paths.get("/Users/someone/Documents/Graph Explorer"))
    )
  }

  test("GX_DIAGRAMS_DIR replaces the folder of new diagrams") {
    assertEquals(
      GxHome.diagramsDir(env("GX_DIAGRAMS_DIR" -> "/tmp/../tmp/diagrams"), home("/Users/someone")),
      Right(Paths.get("/tmp/diagrams"))
    )
  }

  test("GX_HOME does not move the folder of new diagrams") {
    // The diagrams are the files of the person, not the data of gx. A test
    // that moves GX_HOME must also set GX_DIAGRAMS_DIR, or it writes into
    // the real Documents folder.
    assertEquals(
      GxHome.diagramsDir(env("GX_HOME" -> "/tmp/scratch"), home("/Users/someone")),
      Right(Paths.get("/Users/someone/Documents/Graph Explorer"))
    )
  }

  test("a relative GX_DIAGRAMS_DIR is REFUSED, as a relative GX_HOME is") {
    for relative <- List("diagrams", "./diagrams", "../diagrams", "a/b") do
      GxHome.diagramsDir(env("GX_DIAGRAMS_DIR" -> relative), home("/Users/someone")) match
        case Right(path) => fail(s"[$relative] should have been refused, got $path")
        case Left(why) =>
          assert(why.contains("GX_DIAGRAMS_DIR"), s"[$relative] the reason should name the variable: $why")
          assert(why.contains("absolute"), s"[$relative] the reason should name the rule: $why")
          assert(why.contains(relative), s"[$relative] the reason should quote the value: $why")
  }

  test("a blank GX_DIAGRAMS_DIR means unset") {
    for blank <- List("", "   ") do
      assertEquals(
        GxHome.diagramsDir(env("GX_DIAGRAMS_DIR" -> blank), home("/Users/someone")),
        Right(Paths.get("/Users/someone/Documents/Graph Explorer")),
        s"blank value [$blank] should read as unset"
      )
  }

  test("the metadata store is in the library, beside the old records") {
    val root = Paths.get("/tmp/scratch")
    assertEquals(GxHome.metadataDir(root), Paths.get("/tmp/scratch/library/metadata"))
    assertEquals(GxHome.metadataDir(root).getParent, GxHome.libraryDir(root))
  }

  /** The shared fixture that the desktop reads too (`diagrams_dir_from` in
    * main.rs). Neither side can pass by a change to its own code only.
    */
  test("the folder of new diagrams agrees with the shared fixture of the desktop") {
    // The fixture uses POSIX paths. On Windows, `/tmp/x` is not absolute, and
    // the two sides refuse it in the same way, so the fixture does not apply.
    assume(java.io.File.separatorChar == '/', "the fixture has POSIX paths")
    def search(from: Path): Option[Path] =
      Option(from).flatMap: dir =>
        val candidate = dir.resolve("local-protocol").resolve("fixtures").resolve("diagrams-dir.json")
        if Files.isRegularFile(candidate) then Some(candidate) else search(dir.getParent)
    val file    = search(Paths.get("").toAbsolutePath).getOrElse(fail("could not find local-protocol/fixtures/diagrams-dir.json"))
    val fixture = ujson.read(Files.readString(file, StandardCharsets.UTF_8))
    val cases   = fixture("diagramsDir").arr
    assert(cases.sizeIs >= 13, "the fixture set should not have shrunk")
    for c <- cases do
      val name   = c("name").str
      val value  = c("env").strOpt
      val result = GxHome.diagramsDir(key => if key == "GX_DIAGRAMS_DIR" then value else None, home(fixture("home").str))
      (c.obj.get("expect"), c.obj.get("error")) match
        case (Some(expect), None) =>
          assertEquals(result, Right(Paths.get(expect.str)), s"[$name]")
        case (None, Some(parts)) =>
          result match
            case Right(path) => fail(s"[$name] should have been refused, got $path")
            case Left(why)   => for part <- parts.arr do assert(why.contains(part.str), s"[$name] the reason should contain ${part.str}: $why")
        case _ => fail(s"[$name] a case needs `expect` or `error`")
  }

