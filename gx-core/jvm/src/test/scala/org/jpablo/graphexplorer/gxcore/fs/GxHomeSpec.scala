package org.jpablo.graphexplorer.gxcore.fs

import munit.FunSuite

import java.nio.file.{Path, Paths}

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
