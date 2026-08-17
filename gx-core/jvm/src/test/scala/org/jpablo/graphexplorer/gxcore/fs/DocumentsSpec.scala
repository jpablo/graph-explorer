package org.jpablo.graphexplorer.gxcore.fs

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.ContentHash

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}

/** V-01..V-04 and V-16 from docs/desktop-gx-v2-architecture.md §5, asserted by
  * name. The brief's §1 complaint about v1 was that its invariants existed only
  * as side effects of shell smoke scripts; these are the same properties as
  * ordinary tests.
  */
class DocumentsSpec extends FunSuite:

  private val tmp = FunFixture[Path](
    setup = _ => Files.createTempDirectory("gx-core-docs"),
    teardown = dir =>
      Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
  )

  private def posixSupported(p: Path): Boolean =
    p.getFileSystem.supportedFileAttributeViews.contains("posix")

  // ------------------------------------------------------- V-01 conflicts

  tmp.test("V-01: a write with a stale base is rejected AND leaves the file untouched") { dir =>
    val f = dir.resolve("a.dot")
    Documents.create(f, "digraph G { a }").fold(e => fail(s"$e"), identity)
    val original = Documents.read(f).fold(e => fail(s"$e"), identity)

    // Somebody else writes.
    Documents.write(f, "digraph G { b }", original.hash).fold(e => fail(s"$e"), identity)

    // Our baseline is now stale.
    val result = Documents.write(f, "digraph G { mine }", original.hash)
    result match
      case Left(DocumentError.Conflict(_, expected, actual)) =>
        assertEquals(expected, original.hash)
        assertNotEquals(actual, original.hash)
      case other => fail(s"expected a Conflict, got $other")

    // The part that matters: the rejected write changed nothing.
    assertEquals(Files.readString(f), "digraph G { b }")
  }

  tmp.test("a write with the current base succeeds and yields the new identity") { dir =>
    val f       = dir.resolve("a.dot")
    val created = Documents.create(f, "one").fold(e => fail(s"$e"), identity)
    val updated = Documents.write(f, "two", created.hash).fold(e => fail(s"$e"), identity)
    assertEquals(Files.readString(f), "two")
    assertNotEquals(updated.hash, created.hash)
    assertEquals(Documents.hashOf(f), Some(updated.hash))
  }

  /** D1's accepted cost, pinned so it is a decision rather than a surprise:
    * identity follows content, so returning to earlier content returns to its
    * hash. For conflict detection this is correct — if what I based my edit on
    * is what is there now, my edit is safe.
    */
  tmp.test("D1: content round-tripped A -> B -> A returns to A's hash") { dir =>
    val f = dir.resolve("a.dot")
    val a = Documents.create(f, "A").fold(e => fail(s"$e"), identity)
    val b = Documents.write(f, "B", a.hash).fold(e => fail(s"$e"), identity)
    val c = Documents.write(f, "A", b.hash).fold(e => fail(s"$e"), identity)
    assertEquals(c.hash, a.hash)
  }

  // ---------------------------------------------------------- V-02 atomic

  tmp.test("V-02: a concurrent reader never observes a partial write") { dir =>
    val f     = dir.resolve("big.dot")
    val small = "digraph G { a }\n"
    val large = "digraph G {\n" + (1 to 20000).map(i => s"  n$i -> n${i + 1}").mkString("\n") + "\n}\n"
    Documents.create(f, small).fold(e => fail(s"$e"), identity)

    @volatile var partial = Option.empty[String]
    val reader = Thread: () =>
      val deadline = System.nanoTime() + 2_000_000_000L
      while System.nanoTime() < deadline && partial.isEmpty do
        val seen = try Files.readString(f) catch case _: Throwable => small
        if seen != small && seen != large then partial = Some(s"${seen.length} chars")
    reader.start()
    for _ <- 1 to 20 do
      Documents.write(f, large, Documents.hashOf(f).getOrElse(fail("gone"))).fold(e => fail(s"$e"), identity)
      Documents.write(f, small, Documents.hashOf(f).getOrElse(fail("gone"))).fold(e => fail(s"$e"), identity)
    reader.join()
    assertEquals(partial, None, "a reader saw a torn write")
  }

  tmp.test("V-02: a failed write leaves no .tmp litter beside the target") { dir =>
    val f = dir.resolve("a.dot")
    Documents.create(f, "x").fold(e => fail(s"$e"), identity)
    Documents.write(f, "y", ContentHash.fromHex("stale")) // rejected
    val strays = Files.list(dir).toArray.map(_.toString).filter(_.endsWith(".tmp"))
    assertEquals(strays.toList, Nil)
  }

  // ----------------------------------------------------- V-03 permissions

  tmp.test("V-03: a write preserves the target's permission bits") { dir =>
    val f = dir.resolve("a.dot")
    Documents.create(f, "one").fold(e => fail(s"$e"), identity)
    assume(posixSupported(f), "no POSIX view (Windows)")

    val wanted = PosixFilePermissions.fromString("rw-r--r--")
    Files.setPosixFilePermissions(f, wanted)
    val before = Documents.hashOf(f).getOrElse(fail("gone"))
    Documents.write(f, "two", before).fold(e => fail(s"$e"), identity)

    assertEquals(
      Files.getPosixFilePermissions(f),
      wanted,
      "this is the v1 bug at main.rs:1076 — one save silently made the file owner-only"
    )
  }

  // --------------------------------------------------- V-04 line endings

  tmp.test("V-04: a CRLF file stays CRLF after a write") { dir =>
    val f = dir.resolve("crlf.dot")
    Files.write(f, "digraph G {\r\n  a -> b\r\n}\r\n".getBytes(StandardCharsets.UTF_8))
    val before = Documents.read(f).fold(e => fail(s"$e"), identity)
    assertEquals(before.lineEnding, LineEnding.Crlf)

    // Incoming text uses LF, as anything built in the app would.
    Documents.write(f, "digraph G {\n  a -> c\n}\n", before.hash).fold(e => fail(s"$e"), identity)

    val after = Files.readString(f)
    assert(after.contains("\r\n"), "CRLF was replaced by LF")
    assert(!after.replace("\r\n", "").contains("\n"), "mixed endings after write")
  }

  tmp.test("V-04: an LF file is not given CRLF") { dir =>
    val f = dir.resolve("lf.dot")
    Files.write(f, "a\nb\nc\n".getBytes(StandardCharsets.UTF_8))
    val before = Documents.read(f).fold(e => fail(s"$e"), identity)
    Documents.write(f, "a\r\nb\r\nd\r\n", before.hash).fold(e => fail(s"$e"), identity)
    assert(!Files.readString(f).contains("\r"), "LF file gained CRLF")
  }

  /** Why V-04 is not cosmetic. Without it, saving unchanged content to a CRLF
    * file rewrites every line's bytes, so the document's identity changes and
    * the other side sees an edit that never happened.
    */
  tmp.test("V-04 protects D1: rewriting identical content does not change the hash") { dir =>
    val f = dir.resolve("crlf.dot")
    Files.write(f, "a\r\nb\r\n".getBytes(StandardCharsets.UTF_8))
    val before = Documents.read(f).fold(e => fail(s"$e"), identity)
    // The same content the app would hold, in the app's own convention.
    val rewritten = Documents.write(f, "a\nb\n", before.hash).fold(e => fail(s"$e"), identity)
    assertEquals(rewritten.hash, before.hash, "a no-op save changed the document's identity")
  }

  test("line endings: dominant wins, and LF is the default") {
    assertEquals(LineEnding.detect("a\r\nb\r\nc\n"), LineEnding.Crlf)
    assertEquals(LineEnding.detect("a\nb\nc\r\n"), LineEnding.Lf)
    assertEquals(LineEnding.detect("no newlines"), LineEnding.Lf)
    assertEquals(LineEnding.detect(""), LineEnding.Lf)
  }

  // ---------------------------------------------------------- V-16 UTF-8

  tmp.test("V-16: non-ASCII survives a round trip regardless of platform charset") { dir =>
    val f    = dir.resolve("unicode.dot")
    val text = "digraph G { a [label=\"café → 日本語\"]; }"
    Documents.create(f, text).fold(e => fail(s"$e"), identity)
    assertEquals(Documents.read(f).fold(e => fail(s"$e"), _.text), text)
    // Explicitly UTF-8 on disk, not whatever the platform would have chosen.
    assertEquals(Files.readAllBytes(f).toSeq, text.getBytes(StandardCharsets.UTF_8).toSeq)
  }

  /** The hazard P0 found on the Windows runner, which reports windows-1252.
    * Under D1 a platform-default decode would give a different hash for the same
    * file on two machines, and the sync model would show it permanently
    * Diverged with nobody having edited anything.
    */
  tmp.test("V-16: the hash is of UTF-8 bytes, not of a platform-default decode") { dir =>
    val f    = dir.resolve("unicode.dot")
    val text = "café"
    Documents.create(f, text).fold(e => fail(s"$e"), identity)
    assertEquals(Documents.hashOf(f), Some(Hashing.ofText(text, LineEnding.Lf)))
    assertNotEquals(
      Hashing.ofBytes(text.getBytes(StandardCharsets.ISO_8859_1)),
      Hashing.ofText(text, LineEnding.Lf)
    )
  }

  test("hashing text requires stating the line ending, and the two differ") {
    assertNotEquals(Hashing.ofText("a\nb", LineEnding.Lf), Hashing.ofText("a\nb", LineEnding.Crlf))
  }

  // ------------------------------------------------------------- basics

  tmp.test("reading a missing file is NotFound, not an exception") { dir =>
    assertEquals(Documents.read(dir.resolve("nope.dot")), Left(DocumentError.NotFound(dir.resolve("nope.dot").toString)))
    assertEquals(Documents.hashOf(dir.resolve("nope.dot")), None)
  }

  tmp.test("reading a directory is NotAFile") { dir =>
    Documents.read(dir) match
      case Left(DocumentError.NotAFile(_)) => ()
      case other                           => fail(s"expected NotAFile, got $other")
  }

  tmp.test("create refuses to overwrite") { dir =>
    val f = dir.resolve("a.dot")
    Documents.create(f, "one").fold(e => fail(s"$e"), identity)
    Documents.create(f, "two") match
      case Left(DocumentError.AlreadyExists(_)) => assertEquals(Files.readString(f), "one")
      case other                                => fail(s"expected AlreadyExists, got $other")
  }
