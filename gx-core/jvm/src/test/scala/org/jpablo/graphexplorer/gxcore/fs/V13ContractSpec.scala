package org.jpablo.graphexplorer.gxcore.fs

import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.util.control.NonFatal

/** V-13: Scala and Rust agree on canonicalization and the content hash.
  *
  * These two are the join key for the whole library (architecture §4), and they
  * are the one contract this codebase writes twice in two languages. §4 is
  * explicit that writing it twice is not the risk — *drifting* is — and that the
  * answer is a shared fixture set rather than two self-consistent
  * implementations.
  *
  * So this suite and the desktop crate's `v13_*` tests read the SAME files under
  * `local-protocol/fixtures/`. Neither can be made to pass by changing only its
  * own side.
  */
class V13ContractSpec extends FunSuite:

  /** Find the fixtures by walking up from the working directory.
    *
    * sbt's idea of `user.dir` differs between a root-aggregated run and a
    * per-project one, and a wrong guess would fail as "file not found" rather
    * than as a contract violation — which is the kind of red herring this
    * project has paid for before.
    */
  private lazy val fixtureDir: Path =
    def search(from: Path): Option[Path] =
      Option(from).flatMap: dir =>
        val candidate = dir.resolve("local-protocol").resolve("fixtures")
        if Files.isDirectory(candidate) then Some(candidate) else search(dir.getParent)
    search(Paths.get("").toAbsolutePath)
      .getOrElse(fail("could not find local-protocol/fixtures from the working directory"))

  private def fixture(name: String): ujson.Value =
    ujson.read(Files.readString(fixtureDir.resolve(name), StandardCharsets.UTF_8))

  test("V-13: content hashes match the shared fixtures"):
    val cases = fixture("content-hashes.json")("contentHashes").arr
    assert(cases.sizeIs >= 8, "the fixture set should not have shrunk")

    for case_ <- cases do
      val name = case_("name").str
      val bytes =
        case_.obj.get("text") match
          case Some(text) => text.str.getBytes(StandardCharsets.UTF_8)
          case None =>
            case_("hexBytes").str.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
      assertEquals(
        Hashing.ofBytes(bytes).hex,
        case_("sha256").str,
        s"content hash disagrees with the fixture for '$name'"
      )

  /** Build each case's tree, canonicalize its input, compare to the expectation.
    *
    * Everything is relative to a RESOLVED root: on macOS `/tmp` is a symlink to
    * `/private/tmp`, so an absolute expectation would be asserting that rather
    * than the rule under test.
    */
  test("V-13: canonicalization matches the shared fixtures"):
    val cases = fixture("canonicalization.json")("canonicalization").arr
    assert(cases.sizeIs >= 10, "the fixture set should not have shrunk")

    for (case_, index) <- cases.zipWithIndex do
      val name = case_("name").str
      val root = Files.createTempDirectory(s"gx-v13-$index").toRealPath()
      try
        val tree = case_("tree")

        for dir <- tree.obj.get("dirs").map(_.arr).getOrElse(Nil) do
          Files.createDirectories(root.resolve(dir.str))

        for file <- tree.obj.get("files").map(_.arr).getOrElse(Nil) do
          val path = root.resolve(file.str)
          Files.createDirectories(path.getParent)
          Files.writeString(path, "digraph G { a }")

        val linkable =
          tree.obj.get("symlinks").map(_.arr).getOrElse(Nil).forall: link =>
            val from = root.resolve(link("link").str)
            Files.createDirectories(from.getParent)
            try
              Files.createSymbolicLink(from, Paths.get(link("target").str))
              true
            catch
              // Windows needs Developer Mode or elevation. Skipping is honest;
              // silently passing is not.
              case NonFatal(_) => false

        if !linkable then println(s"V-13: skipping '$name' — cannot create symlinks here")
        else
          val expectedRel =
            case_.obj.get("expectCaseInsensitive").filter(_ => caseInsensitive) match
              case Some(alt) => alt.str
              case None      => case_("expect").str

          val actual   = FileOrigins.canonicalize(root.resolve(case_("input").str), root)
          val expected = root.resolve(expectedRel)
          assertEquals(actual.toString, expected.toString, s"canonicalization disagrees for '$name'")
      finally deleteTree(root)

  /** The rule with the most consequence and, before V-13, no test on either
    * side: a document's identity must not depend on whether the file happened
    * to exist when it was first named.
    */
  test("V-13: a path keeps its identity across creation"):
    val root = Files.createTempDirectory("gx-v13-identity").toRealPath()
    try
      val dir = Files.createDirectories(root.resolve("real"))
      val file = dir.resolve("later.dot")

      val before = FileOrigins.canonicalize(file, root)
      Files.writeString(file, "digraph G { a }")
      val after = FileOrigins.canonicalize(file, root)

      assertEquals(before.toString, after.toString)
    finally deleteTree(root)

  /** Asked of the filesystem rather than of the OS name: a case-sensitive volume
    * on macOS exists, and D2.1b's lesson is about measuring the thing instead of
    * a proxy for it.
    */
  private lazy val caseInsensitive: Boolean =
    val dir = Files.createTempDirectory("gx-case-probe")
    try
      Files.writeString(dir.resolve("probe.dot"), "x")
      Files.exists(dir.resolve("PROBE.DOT"))
    finally deleteTree(dir)

  private def deleteTree(root: Path): Unit =
    try
      Files
        .walk(root)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(p => try Files.deleteIfExists(p) catch case NonFatal(_) => ())
    catch case NonFatal(_) => ()
