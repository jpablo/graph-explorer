// P0 gate for docs/desktop-gx-v2-architecture.md D2 — "gx is Scala on native-image".
//
// This is NOT a hello-world. It exercises, under a native-image binary, every
// capability D2 assumes and the macOS spike left unverified:
//
//   1. the real production parse path (graphviz port -> dot_json -> upickle -> ViewerGraph)
//   2. MessageDigest — content hashing (D1). A classic native-image failure point,
//      because security providers are registered reflectively.
//   3. java.nio atomic write: temp + fsync + ATOMIC_MOVE (v2 V-02)
//   4. permission preservation (v2 V-03 — the bug at main.rs:1076)
//   5. toRealPath canonicalization, incl. the macOS case-insensitivity trap
//      (sources-and-library-architecture.md §4.2)
//   6. a poll-based change watch (v2 §7 — the 15ms/50ms budget)
//
// Exits non-zero if any check fails, so CI is a real gate rather than a smoke test.

import org.jpablo.graphexplorer.graphviz.Graphviz as ScalaGraphviz
import org.jpablo.graphexplorer.graphviz.dotlang.DotParser
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.{
  SimpleGraph,
  toViewerGraph as dotJsonToViewerGraph
}
import upickle.default.read

import java.nio.file.{Files, Path, Paths, StandardCopyOption, StandardOpenOption}
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

// ---------------------------------------------------------------- harness

private var failures = 0

private def check(name: String)(body: => Either[String, String]): Unit =
  val outcome =
    try body
    catch case e: Throwable => Left(s"threw ${e.getClass.getName}: ${e.getMessage}")
  outcome match
    case Right(detail) => println(s"  PASS  $name${if detail.isEmpty then "" else s" — $detail"}")
    case Left(reason) =>
      failures += 1
      println(s"  FAIL  $name — $reason")

/** Reported but never fatal: platform-dependent facts we want recorded per OS
  * rather than asserted, because the correct answer differs by filesystem.
  */
private def observe(name: String)(body: => String): Unit =
  val detail =
    try body
    catch case e: Throwable => s"n/a (${e.getClass.getSimpleName})"
  println(s"  NOTE  $name — $detail")

private def sha256(bytes: Array[Byte]): String =
  MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

// ------------------------------------------------------- the gx-core write path

/** What v2 D2.2 `document` must do, and what v1's `write_file_atomic` gets wrong:
  * fsync before rename (brief §3), and preserve the target's permissions (V-03).
  */
private def writeFileAtomic(target: Path, text: String): Unit =
  val dir = target.getParent
  // Windows has no POSIX permission view, so this is best-effort by design —
  // preserving permissions must not be able to fail a write.
  val existing =
    if !Files.exists(target) then None
    else
      try Some(Files.getPosixFilePermissions(target))
      catch case _: UnsupportedOperationException => None
  val temp    = Files.createTempFile(dir, s".${target.getFileName}", ".tmp")
  val channel = java.nio.channels.FileChannel.open(temp, StandardOpenOption.WRITE)
  try
    channel.write(java.nio.ByteBuffer.wrap(text.getBytes("UTF-8")))
    channel.force(true) // the fsync v1 never had
  finally channel.close()
  existing.foreach(perms => Files.setPosixFilePermissions(temp, perms))
  Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)

// ---------------------------------------------------------------- the checks

private val sampleDot =
  """digraph G {
    |  subgraph cluster_c1 { label="C1"; a -> b; b -> c }
    |  c -> d
    |  d -> e
    |  a -> e
    |}
    |""".stripMargin

@main def gxSpike(args: String*): Unit =
  // V-14 names the cold start of a PARSE-ONLY command, so that is what has to be
  // timed. Timing the whole check suite instead measures three fsyncs, a symlink
  // and a poll loop against a budget that describes none of them.
  if args.contains("--bench-parse") then
    DotParser.parse(sampleDot) match
      case Left(err) => System.err.println(err); sys.exit(1)
      case Right(_)  => sys.exit(0)

  // Baseline: process spawn + native runtime init and nothing else. On a shared
  // CI runner that term dominates — a 3-vCPU macOS runner spends ~200ms here
  // where a dev laptop spends ~5ms — so an ABSOLUTE cold-start number measures
  // the runner, not the binary. (parse - noop) is the part this code controls.
  if args.contains("--bench-noop") then sys.exit(0)

  println(s"gx native-image P0 gate — ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
  val tmp = Files.createTempDirectory("gx-spike")

  check("parse-only (the query path — D2.3)") {
    DotParser.parse(sampleDot) match
      case Left(err) => Left(err)
      case Right(_)  => Right("DotParser.parse ok")
  }

  check("full parse + layout (the render path)") {
    val result = ScalaGraphviz.renderFormats(sampleDot, Seq("dot_json"))
    result.output.get("dot_json") match
      case None => Left(s"render failed: ${result.errors.mkString("; ")}")
      case Some(json) =>
        val g = dotJsonToViewerGraph(read[SimpleGraph](json))
        if g.nodes.size == 5 then Right(s"${g.nodes.size} nodes, ${g.arrows.size} arrows")
        else Left(s"expected 5 nodes, got ${g.nodes.size}")
  }

  check("MessageDigest SHA-256 (D1 content hashing)") {
    val h = sha256("hello".getBytes("UTF-8"))
    val expected = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
    if h == expected then Right(h.take(16) + "…") else Left(s"got $h")
  }

  check("atomic write: temp + fsync + ATOMIC_MOVE (V-02)") {
    val f = tmp.resolve("doc.dot")
    writeFileAtomic(f, sampleDot)
    val roundTrip = Files.readString(f)
    if roundTrip == sampleDot then Right(s"${Files.size(f)} bytes") else Left("content mismatch")
  }

  check("permission preservation across write (V-03)") {
    val f = tmp.resolve("perms.dot")
    Files.writeString(f, "digraph G {}")
    if !f.getFileSystem.supportedFileAttributeViews.contains("posix") then
      Right("skipped: no POSIX view (Windows)")
    else
      val wanted = PosixFilePermissions.fromString("rw-r--r--")
      Files.setPosixFilePermissions(f, wanted)
      writeFileAtomic(f, "digraph G { a }")
      val after = Files.getPosixFilePermissions(f)
      if after == wanted then Right(PosixFilePermissions.toString(after))
      else Left(s"0644 became ${PosixFilePermissions.toString(after)} — this is the main.rs:1076 bug")
  }

  check("toRealPath canonicalization (§4.2)") {
    val f = tmp.resolve("canon.dot")
    Files.writeString(f, "digraph G {}")
    val viaDots = tmp.resolve("sub").resolve("..").resolve("canon.dot")
    Files.createDirectories(tmp.resolve("sub"))
    val a = f.toRealPath()
    val b = viaDots.toRealPath()
    if a == b then Right("`..` resolves to one path")
    else Left(s"$a != $b")
  }

  check("watch: a poll loop observes a change (v2 §7)") {
    val f = tmp.resolve("watched.dot")
    Files.writeString(f, "digraph G { a }")
    val before = sha256(Files.readAllBytes(f))
    Files.writeString(f, "digraph G { a; b }")
    var seen  = false
    var polls = 0
    while !seen && polls < 200 do
      if sha256(Files.readAllBytes(f)) != before then seen = true
      else { polls += 1; Thread.sleep(15) }
    if seen then Right(s"detected after $polls polls") else Left("change never observed")
  }

  // Platform facts worth recording per-OS rather than asserting.
  observe("filesystem case sensitivity") {
    val lower = tmp.resolve("CaseTest.dot")
    Files.writeString(lower, "digraph G {}")
    val upper = tmp.resolve("casetest.dot")
    if Files.exists(upper) then "INSENSITIVE — two spellings, one file (the §4.2 trap)"
    else "sensitive"
  }
  observe("symlink support") {
    val target = tmp.resolve("real.dot")
    Files.writeString(target, "digraph G {}")
    val link = tmp.resolve("link.dot")
    Files.createSymbolicLink(link, target)
    if link.toRealPath() == target.toRealPath() then "resolves to target" else "DIVERGES"
  }
  observe("path separator / default charset") {
    s"'${java.io.File.separator}' / ${java.nio.charset.Charset.defaultCharset()}"
  }

  println()
  if failures == 0 then println("P0 checks: ALL PASS")
  else
    println(s"P0 checks: $failures FAILED")
    sys.exit(1)
