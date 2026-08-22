package org.jpablo.graphexplorer.gxcore.model

import munit.FunSuite

/** Encoding rules from docs/sources-and-library-architecture.md §4.2.
  *
  * Every case here is one v1 got wrong or would have. The bug at
  * `desktop/src-tauri/src/main.rs:921-934` survived five months because a
  * hand-rolled decoder replaced `%2F` and nothing else: a plain POSIX path
  * worked, so nothing looked broken until a path had a space in it — or was a
  * Windows path, where every separator is escaped.
  */
class OriginUriSpec extends FunSuite:

  private def roundTrip(path: String): String =
    OriginUri.fromCanonicalPath(path).filePath.getOrElse(fail(s"no file path for $path"))

  test("a plain POSIX path round trips") {
    val p = "/Users/jpablo/diagrams/arch.dot"
    assertEquals(OriginUri.fromCanonicalPath(p).value, "file:///Users/jpablo/diagrams/arch.dot")
    assertEquals(roundTrip(p), p)
  }

  /** The case that actually broke v1: a space encodes to `%20`, and a decoder
    * that only knows `%2F` leaves it mangled, so the path misses the watch
    * registry and `get` fails on a file `watch` just accepted.
    */
  test("a path containing spaces round trips") {
    val p = "/Users/jpablo/My Diagrams/system design.dot"
    val u = OriginUri.fromCanonicalPath(p)
    assert(u.value.contains("%20"), s"space was not encoded: ${u.value}")
    assertEquals(roundTrip(p), p)
  }

  /** Non-ASCII must encode as UTF-8 bytes, not UTF-16 code units. Getting this
    * wrong is invisible until someone names a file in their own language.
    */
  test("a non-ASCII path round trips as UTF-8 bytes") {
    val p = "/Users/jpablo/diagramas/café/日本語.dot"
    val u = OriginUri.fromCanonicalPath(p)
    assert(!u.value.contains("é"), s"non-ASCII left raw: ${u.value}")
    assert(u.value.contains("%C3%A9"), s"'é' is not its UTF-8 bytes: ${u.value}")
    assertEquals(roundTrip(p), p)
  }

  test("reserved characters in a filename survive") {
    val p = "/tmp/a=b&c#d?e.dot"
    val u = OriginUri.fromCanonicalPath(p)
    for raw <- List("&", "#", "?", "=") do
      assert(!u.value.contains(raw), s"'$raw' left raw in ${u.value}")
    assertEquals(roundTrip(p), p)
  }

  test("separators are separators, not encoded characters") {
    val u = OriginUri.fromCanonicalPath("/a/b/c.dot")
    assert(!u.value.contains("%2F"), s"'/' was encoded: ${u.value}")
  }

  // ------------------------------------------------------------- Windows

  /** The round trip is NOT the identity here, and that is the pinned answer
    * rather than an accident: the encoder writes URI separators.
    *
    * Callers that hand the result to `Paths.get` are unaffected — it accepts
    * either separator. A caller that COMPARES it to a path another process
    * reported is not: the desktop shell writes the platform's own separator, so
    * the comparison misses, and misses silently.
    */
  test("a Windows path becomes a file URI with a bare drive letter") {
    val u = OriginUri.fromCanonicalPath("C:\\Users\\jpablo\\arch.dot")
    assertEquals(u.value, "file:///C:/Users/jpablo/arch.dot")
    assertEquals(roundTrip("C:\\Users\\jpablo\\arch.dot"), "C:/Users/jpablo/arch.dot")
  }

  /** `toRealPath` hands back the `\\?\` verbatim prefix for long paths. A naive
    * encoder turns its `?` into `%3F` and the identity is silently wrong.
    */
  test("the Windows verbatim prefix is stripped, not encoded") {
    val u = OriginUri.fromCanonicalPath("\\\\?\\C:\\Users\\jpablo\\arch.dot")
    assertEquals(u.value, "file:///C:/Users/jpablo/arch.dot")
    assert(!u.value.contains("%3F"), s"verbatim prefix leaked: ${u.value}")
  }

  test("a Windows path with spaces round trips") {
    val u = OriginUri.fromCanonicalPath("C:\\Users\\jpablo\\My Diagrams\\a.dot")
    assertEquals(u.value, "file:///C:/Users/jpablo/My%20Diagrams/a.dot")
  }

  // ------------------------------------------------------- identity rules

  /** The property the library depends on: encoding is stable, so a URI used as a
    * key does not drift between the write and the lookup.
    */
  test("encoding is idempotent through a parse") {
    for p <- List("/a/b.dot", "/a b/c.dot", "/café/ü.dot", "C:\\a b\\c.dot") do
      val once = OriginUri.fromCanonicalPath(p)
      val back = OriginUri.parse(once.value).fold(e => fail(e), identity)
      assertEquals(back.value, once.value, s"not idempotent for $p")
  }

  test("scheme is recognised, and unknown schemes are rejected rather than assumed") {
    assertEquals(OriginUri.fromCanonicalPath("/a.dot").scheme, OriginScheme.File)
    assertEquals(OriginUri.parse("https://example.com/a.dot").map(_.scheme), Right(OriginScheme.Https))
    assert(OriginUri.parse("postgres://host/db").isLeft)
    assert(OriginUri.parse("/not/a/uri").isLeft)
  }

  test("filePath is empty for non-file schemes") {
    val https = OriginUri.parse("https://example.com/a.dot").fold(e => fail(e), identity)
    assertEquals(https.filePath, None)
  }

  // --------------------------------------------------- scheme capabilities

  test("a scheme rejects a mode it cannot support, with a reason") {
    assertEquals(OriginScheme.Https.rejectionFor(SyncMode.Pull), None)
    assertEquals(OriginScheme.Https.rejectionFor(SyncMode.Detached), None)
    assert(OriginScheme.Https.rejectionFor(SyncMode.Push).exists(_.contains("https")))
    assert(OriginScheme.Https.rejectionFor(SyncMode.Sync).isDefined)
  }

  test("file supports every mode") {
    for m <- SyncMode.values do assertEquals(OriginScheme.File.rejectionFor(m), None, s"mode $m")
  }
