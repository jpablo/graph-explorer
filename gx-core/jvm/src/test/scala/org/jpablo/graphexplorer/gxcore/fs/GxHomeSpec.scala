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
      GxHome.resolve(env(), home("/Users/someone")),
      Paths.get("/Users/someone/.graph-explorer")
    )
  }

  test("GX_HOME replaces the directory, it does not nest inside it") {
    // The variable names the data directory itself. Resolving it to
    // `$GX_HOME/.graph-explorer` would put a hidden directory inside a path the
    // caller chose explicitly, which is the opposite of what naming one is for.
    assertEquals(
      GxHome.resolve(env("GX_HOME" -> "/tmp/scratch"), home("/Users/someone")),
      Paths.get("/tmp/scratch")
    )
  }

  test("a relative GX_HOME is absolutised, so later path comparisons agree") {
    // The library store decides whether a name escapes its directory by
    // comparing paths. A relative root would make that comparison depend on the
    // process's working directory, which `gx` deliberately reads from the
    // user's shell rather than its own.
    val resolved = GxHome.resolve(env("GX_HOME" -> "scratch"), home("/Users/someone"))
    assert(resolved.isAbsolute, s"expected an absolute path, got $resolved")
    assertEquals(resolved.getFileName.toString, "scratch")
  }

  test("a blank GX_HOME means unset, not the filesystem root") {
    // `export GX_HOME="$SOMETHING"` with SOMETHING unset is how a shell script
    // passes through an absent value. Taking it literally would put the library
    // at `/library`.
    for blank <- List("", "   ") do
      assertEquals(
        GxHome.resolve(env("GX_HOME" -> blank), home("/Users/someone")),
        Paths.get("/Users/someone/.graph-explorer"),
        s"blank value [$blank] should read as unset"
      )
  }

  test("library and runtime hang off the same root") {
    // They are only useful as a set: the runtime file names the socket for the
    // library it belongs to, so a half-applied override splits them silently.
    val root = GxHome.resolve(env("GX_HOME" -> "/tmp/scratch"), home("/Users/someone"))
    assertEquals(GxHome.libraryDir(root), Paths.get("/tmp/scratch/library"))
    assertEquals(GxHome.runtimeDir(root), Paths.get("/tmp/scratch/runtime"))
  }
