package org.jpablo.graphexplorer.gxcore.fs

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.{ContentHash, OriginUri}

import java.nio.file.{Files, Path, Paths}

/** V-07 (policy on the resolved path) and V-08 (audit), plus the
  * canonicalization rules from docs/sources-and-library-architecture.md §4.2.
  */
class PolicySpec extends FunSuite:

  private val tmp = FunFixture[Path](
    setup = _ => Files.createTempDirectory("gx-core-policy").toRealPath(),
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

  // ------------------------------------------------- V-07 resolved paths

  /** The substance of V-07. A symlink INSIDE an allowed root pointing OUTSIDE it
    * is an allowed path naming a denied file; only resolving first catches it.
    */
  tmp.test("V-07: a symlink escaping the allowed root is denied") { dir =>
    assume(symlinksWork(dir), "symlinks unavailable")
    val allowed = Files.createDirectories(dir.resolve("workspace"))
    val outside = Files.createDirectories(dir.resolve("elsewhere"))
    val secret  = outside.resolve("secret.dot")
    Files.writeString(secret, "digraph G { secret }")

    val escape = allowed.resolve("innocent.dot")
    Files.createSymbolicLink(escape, secret)

    val policy = AccessPolicy(allowedRoots = List(allowed), deniedRoots = Nil)
    policy.evaluate(escape) match
      case Left(denial) => assert(denial.reason.contains("outside"), denial.reason)
      case Right(p)     => fail(s"symlink escape was allowed, resolving to $p")
  }

  tmp.test("V-07: a real file inside the allowed root is allowed") { dir =>
    val allowed = Files.createDirectories(dir.resolve("workspace"))
    val f       = allowed.resolve("a.dot")
    Files.writeString(f, "digraph G {}")
    val policy = AccessPolicy(allowedRoots = List(allowed), deniedRoots = Nil)
    assert(policy.evaluate(f).isRight)
  }

  /** `..` must be collapsed before evaluation, or an allowed prefix smuggles a
    * path out of its own root.
    */
  tmp.test("V-07: dot-dot traversal out of the allowed root is denied") { dir =>
    val allowed = Files.createDirectories(dir.resolve("workspace"))
    val outside = Files.createDirectories(dir.resolve("elsewhere"))
    Files.writeString(outside.resolve("secret.dot"), "x")
    val sneaky = allowed.resolve("..").resolve("elsewhere").resolve("secret.dot")

    val policy = AccessPolicy(allowedRoots = List(allowed), deniedRoots = Nil)
    assert(policy.evaluate(sneaky).isLeft, "traversal via .. was allowed")
  }

  tmp.test("denial beats permission: a denied root inside an allowed root still loses") { dir =>
    val allowed = Files.createDirectories(dir.resolve("workspace"))
    val denied  = Files.createDirectories(allowed.resolve("secrets"))
    val f       = denied.resolve("keys.dot")
    Files.writeString(f, "x")

    val policy = AccessPolicy(allowedRoots = List(allowed), deniedRoots = List(denied))
    policy.evaluate(f) match
      case Left(d)  => assert(d.reason.contains("denied root"), d.reason)
      case Right(_) => fail("a denied root inside an allowed root was permitted")
  }

  /** D6, stated as a decision rather than an accident: an empty allowlist means
    * allow-all, because the principals this binds already run as the user.
    */
  tmp.test("D6: an empty allowlist allows, but the denylist still applies") { dir =>
    val f = dir.resolve("a.dot")
    Files.writeString(f, "x")
    assert(AccessPolicy(Nil, Nil).evaluate(f).isRight)
    assert(AccessPolicy(Nil, List(dir)).evaluate(f).isLeft)
  }

  test("the default policy denies credential directories") {
    val home   = Paths.get(sys.props.getOrElse("user.home", "/tmp"))
    val policy = AccessPolicy.permissive
    for dir <- List(".ssh", ".aws", ".gnupg", ".kube") do
      assert(
        policy.evaluate(home.resolve(dir).resolve("config")).isLeft,
        s"~/$dir was not denied by default"
      )
  }

  test("policy reads v1's environment variables, so existing setups keep working") {
    val sep = java.io.File.pathSeparator
    val p = AccessPolicy.fromEnv:
      case "GX_ALLOWED_ROOTS" => Some(s"/tmp/one${sep}/tmp/two")
      case _                  => None
    assertEquals(p.allowedRoots.map(_.toString).toSet, Set("/tmp/one", "/tmp/two"))
    assert(p.deniedRoots.nonEmpty, "defaults were dropped when the env supplied an allowlist")
  }

  // ------------------------------------------------- §4.2 canonicalization

  tmp.test("§4.2: canonicalization is idempotent") { dir =>
    val f = dir.resolve("a.dot")
    Files.writeString(f, "x")
    val once  = FileOrigins.canonicalize(f, dir)
    val twice = FileOrigins.canonicalize(once, dir)
    assertEquals(twice, once)
  }

  tmp.test("§4.2: a relative path resolves against the GIVEN cwd, not the process's") { dir =>
    val f = dir.resolve("a.dot")
    Files.writeString(f, "x")
    assertEquals(FileOrigins.canonicalize(Paths.get("a.dot"), dir), f.toRealPath())
  }

  tmp.test("§4.2: a symlink and its target produce ONE origin") { dir =>
    assume(symlinksWork(dir), "symlinks unavailable")
    val target = dir.resolve("real.dot")
    Files.writeString(target, "x")
    val link = dir.resolve("link.dot")
    Files.createSymbolicLink(link, target)
    assertEquals(FileOrigins.originOf(link, dir), FileOrigins.originOf(target, dir))
  }

  /** P0 measured this: macOS and Windows report case-insensitive filesystems,
    * Linux does not. Where the filesystem says two spellings are one file, the
    * origin must agree — otherwise two library records bind to one file and
    * fight over it.
    */
  tmp.test("§4.2: on a case-insensitive filesystem, two spellings give ONE origin") { dir =>
    val f = dir.resolve("CaseTest.dot")
    Files.writeString(f, "x")
    val lower = dir.resolve("casetest.dot")
    assume(Files.exists(lower), "case-sensitive filesystem")
    assertEquals(
      FileOrigins.originOf(lower, dir),
      FileOrigins.originOf(f, dir),
      "two spellings of one file produced two origins"
    )
  }

  /** v1 fell back to the un-canonicalized path when the file did not exist,
    * which let `..` and unresolved symlinks reach the policy check.
    */
  tmp.test("§4.2: a not-yet-created file still canonicalizes, with .. collapsed") { dir =>
    val notYet = dir.resolve("sub").resolve("..").resolve("new.dot")
    val canon  = FileOrigins.canonicalize(notYet, dir)
    assertEquals(canon, dir.resolve("new.dot"))
    assert(!canon.toString.contains(".."), s"traversal survived: $canon")
  }

  tmp.test("§4.2: a path with a space becomes a valid origin and comes back") { dir =>
    val sub = Files.createDirectories(dir.resolve("My Diagrams"))
    val f   = sub.resolve("system design.dot")
    Files.writeString(f, "x")
    val origin = FileOrigins.originOf(f, dir)
    assert(origin.value.contains("%20"), origin.value)
    assertEquals(origin.filePath.map(Paths.get(_)), Some(f.toRealPath()))
  }

  // ----------------------------------------------------------- V-08 audit

  tmp.test("V-08: allow, deny, write and conflict all reach the log") { dir =>
    val log   = dir.resolve("audit.log.jsonl")
    val audit = Audit(log)
    val hash  = ContentHash.fromHex("ab")

    audit.record(AuditEvent.Allowed("/a.dot", "watch"))
    audit.record(AuditEvent.Denied("/b.dot", "outside allowed roots"))
    audit.record(AuditEvent.Written("/a.dot", hash, "cli"))
    audit.record(AuditEvent.Conflict("/a.dot", hash, ContentHash.fromHex("cd"), "ui"))

    val lines = audit.entries
    assertEquals(lines.size, 4)
    assert(lines(0).contains("\"event\":\"allowed\""), lines(0))
    assert(lines(1).contains("\"event\":\"denied\""), lines(1))
    assert(lines(2).contains("\"event\":\"written\"") && lines(2).contains("\"source\":\"cli\""), lines(2))
    assert(lines(3).contains("\"event\":\"conflict\"") && lines(3).contains("\"source\":\"ui\""), lines(3))
    assert(lines.forall(_.contains("\"timestampMs\":")), "an entry has no timestamp")
  }

  tmp.test("V-08: a path with quotes or newlines cannot break the JSON") { dir =>
    val audit = Audit(dir.resolve("audit.log.jsonl"))
    audit.record(AuditEvent.Denied("/tmp/\"quoted\"\nand\\slashed.dot", "nope"))
    val line = audit.entries.head
    assertEquals(line.count(_ == '\n'), 0)
    assert(line.contains("\\\""), line)
    assert(line.contains("\\n"), line)
    assert(line.endsWith("}"), line)
  }

  /** Failing to record must never fail the thing being recorded: a full disk
    * should not turn the editor read-only.
    */
  tmp.test("V-08: an unwritable log does not raise") { dir =>
    val audit = Audit(dir.resolve("a.dot").resolve("nested").resolve("audit.jsonl"))
    Files.writeString(dir.resolve("a.dot"), "not a directory")
    audit.record(AuditEvent.Allowed("/x", "read")) // must not throw
    assertEquals(audit.entries, Vector.empty)
  }
