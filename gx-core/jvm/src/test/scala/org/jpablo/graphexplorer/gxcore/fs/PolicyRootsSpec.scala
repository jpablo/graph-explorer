package org.jpablo.graphexplorer.gxcore.fs

import munit.FunSuite

import java.nio.file.{Files, Path}

/** The roots of an access policy must name the same files as the canonical
  * path that `evaluate` compares with them.
  *
  * `evaluate` changes the path to its canonical form, and it follows each
  * symlink. But it used the roots as a person gave them. Thus a root with a
  * symlink in it matched no canonical path. A denied root then stopped
  * nothing, and an allowed root refused each file under it. On macOS, `/tmp`
  * and the temp folder (`/var/folders/...`) are under a symlink, so an
  * allowed root there refused each file.
  *
  * PolicySpec calls `toRealPath` on its temp folder. Its roots are canonical,
  * so it does not show this.
  */
class PolicyRootsSpec extends FunSuite:

  private val tmp = FunFixture[Path](
    // No toRealPath here: the roots that a person gives are not canonical.
    setup = _ => Files.createTempDirectory("gx-core-roots"),
    teardown = dir =>
      Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
  )

  private def symlinksWork(dir: Path): Boolean =
    try
      val t = dir.resolve("probe-target")
      Files.writeString(t, "x")
      Files.createSymbolicLink(dir.resolve("probe-link"), t)
      true
    catch case _: Throwable => false // Windows without developer mode

  /** `dir/real-<name>` with the file `a.dot` in it, and the symlink
    * `dir/<name>` to that folder.
    */
  private def linkedFolder(dir: Path, name: String): (Path, Path) =
    val real = Files.createDirectories(dir.resolve(s"real-$name"))
    Files.writeString(real.resolve("a.dot"), "digraph G { a }")
    val link = Files.createSymbolicLink(dir.resolve(name), real)
    (real, link)

  tmp.test("a denied root that is a symlink denies a file through the link") { dir =>
    assume(symlinksWork(dir), "symlinks unavailable")
    val (_, link) = linkedFolder(dir, "ssh")
    val policy    = AccessPolicy(allowedRoots = Nil, deniedRoots = List(link))
    val result    = policy.evaluate(link.resolve("a.dot"))
    assert(result.isLeft, s"the denied root $link did not deny a file under it: $result")
  }

  tmp.test("a denied root that is a symlink denies the real file too") { dir =>
    assume(symlinksWork(dir), "symlinks unavailable")
    val (real, link) = linkedFolder(dir, "ssh")
    val policy       = AccessPolicy(allowedRoots = Nil, deniedRoots = List(link))
    val result       = policy.evaluate(real.resolve("a.dot"))
    assert(result.isLeft, s"the denied root $link did not deny the file that it names: $result")
  }

  tmp.test("an allowed root that is a symlink allows a file under it") { dir =>
    assume(symlinksWork(dir), "symlinks unavailable")
    val (_, link) = linkedFolder(dir, "work")
    val policy    = AccessPolicy(allowedRoots = List(link), deniedRoots = Nil)
    val result    = policy.evaluate(link.resolve("a.dot"))
    assert(result.isRight, s"the allowed root $link refused a file under it: $result")
  }

  tmp.test("an allowed root in the temp folder allows a file under it") { dir =>
    // No symlink is made here. On macOS, the temp folder is under one already.
    val file = dir.resolve("a.dot")
    Files.writeString(file, "digraph G { a }")
    val policy = AccessPolicy(allowedRoots = List(dir), deniedRoots = Nil)
    val result = policy.evaluate(file)
    assert(result.isRight, s"the allowed root $dir refused a file under it: $result")
  }

  tmp.test("GX_DENY_ROOTS with a symlink denies a file through the link") { dir =>
    assume(symlinksWork(dir), "symlinks unavailable")
    val (_, link) = linkedFolder(dir, "secrets")
    val policy    = AccessPolicy.fromEnv(Map("GX_DENY_ROOTS" -> link.toString).get)
    val result    = policy.evaluate(link.resolve("a.dot"))
    assert(result.isLeft, s"GX_DENY_ROOTS=$link did not deny a file under it: $result")
  }
