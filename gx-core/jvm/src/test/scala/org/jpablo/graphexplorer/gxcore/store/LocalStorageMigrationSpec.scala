package org.jpablo.graphexplorer.gxcore.store

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.*

import java.nio.file.{Files, Path}

/** Migration runs once, against a user's entire accumulated library, and the
  * failure mode of getting it wrong is not "a bad import" but "their work is
  * gone". These tests are mostly about what it must NOT do.
  */
class LocalStorageMigrationSpec extends FunSuite:

  private val tmp = FunFixture[Path](
    setup = _ => Files.createTempDirectory("gx-core-migration").toRealPath(),
    teardown = dir =>
      Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
  )

  private def directory(entries: (String, String)*): String =
    val projects = entries
      .map: (id, name) =>
        s"""{"id":{"value":"$id"},"name":"$name","lastModified":111,"createdAt":100}"""
      .mkString(",")
    s"""{"projects":[$projects]}"""

  private def payload(text: String, name: String = "Untitled"): String =
    s"""{
       |  "hiddenElements": {"ids": [{"$$type":"NodeId","value":"n1"}]},
       |  "collapsedGroups": [{"$$type":"GroupId","value":"g1"}],
       |  "projectName": "$name",
       |  "source": ${ujson.Str(text).render()},
       |  "format": "DOT"
       |}""".stripMargin

  private def store(dir: Path) =
    val s = LibraryStore(dir)
    s.initialize()
    s

  // ------------------------------------------------------------- basics

  tmp.test("a project becomes a diagram, with its text under the new name") { dir =>
    val s      = store(dir)
    val report = LocalStorageMigration
      .migrate(directory("p1" -> "Architecture"), _ => Some(payload("digraph G { a }")), s)
      .fold(e => fail(e), identity)

    assertEquals(report.imported.size, 1)
    val d = s.get(LocalStorageMigration.idFor("p1")).fold(e => fail(s"$e"), identity)
    assertEquals(d.name, "Architecture")
    // The old `source` field held the TEXT; the new model calls that `text` and
    // reserves origin/source for where it came from — the opposite meaning.
    assertEquals(d.text, "digraph G { a }")
    assertEquals(d.format, "DOT")
    assertEquals(d.createdAt, 100L)
    assertEquals(d.updatedAt, 111L)
  }

  tmp.test("a migrated diagram has no binding, because it never came from a file") { dir =>
    val s = store(dir)
    LocalStorageMigration.migrate(directory("p1" -> "A"), _ => Some(payload("x")), s).fold(e => fail(e), identity)
    assertEquals(s.list().head.binding, None)
  }

  /** View state is recovered from JSON written by an older version whose types
    * have since changed, so parsing is lenient — and `$type` discriminators must
    * not arrive as element ids.
    */
  tmp.test("view state is recovered leniently, without picking up type tags") { dir =>
    val s = store(dir)
    LocalStorageMigration.migrate(directory("p1" -> "A"), _ => Some(payload("x")), s).fold(e => fail(e), identity)
    val meta = s.list().head.metadata
    assertEquals(meta.hiddenElements, Set("n1"))
    assertEquals(meta.collapsedGroups, Set("g1"))
  }

  tmp.test("a payload in an unrecognised shape still yields the diagram, minus view state") { dir =>
    val s = store(dir)
    val odd = """{"source":"digraph G {}","hiddenElements":"who knows"}"""
    LocalStorageMigration.migrate(directory("p1" -> "A"), _ => Some(odd), s).fold(e => fail(e), identity)
    val d = s.list().head
    assertEquals(d.text, "digraph G {}")
    assertEquals(d.metadata.hiddenElements, Set("who knows")) // best effort, never fatal
  }

  // -------------------------------------------------------- idempotence

  /** Idempotence by derived key rather than a "have I run?" flag, because a flag
    * can be lost, or written before the work finishes.
    */
  tmp.test("running twice imports nothing the second time") { dir =>
    val s   = store(dir)
    val dj  = directory("p1" -> "A", "p2" -> "B")
    val pay = (_: String) => Some(payload("digraph G {}"))

    val first = LocalStorageMigration.migrate(dj, pay, s).fold(e => fail(e), identity)
    assertEquals(first.imported.size, 2)

    val second = LocalStorageMigration.migrate(dj, pay, s).fold(e => fail(e), identity)
    assertEquals(second.imported, Vector.empty)
    assertEquals(second.skipped.size, 2)
    assertEquals(s.list().size, 2, "a second run duplicated records")
  }

  /** The rule the whole design hangs on. Re-running migration must never revert
    * work done since the first run.
    */
  tmp.test("re-running does NOT overwrite edits made since the first run") { dir =>
    val s   = store(dir)
    val dj  = directory("p1" -> "A")
    val pay = (_: String) => Some(payload("original"))
    LocalStorageMigration.migrate(dj, pay, s).fold(e => fail(e), identity)

    val id      = LocalStorageMigration.idFor("p1")
    val edited  = s.get(id).fold(e => fail(s"$e"), identity).copy(text = "edited since")
    s.save(edited).fold(e => fail(s"$e"), identity)

    LocalStorageMigration.migrate(dj, pay, s).fold(e => fail(e), identity)
    assertEquals(s.get(id).fold(e => fail(s"$e"), _.text), "edited since")
  }

  // ------------------------------------------------------ never destroy

  /** The hazard `ProjectsStorage` already carries scar tissue for: an empty
    * index reaching persistence and being written over real data.
    */
  tmp.test("an EMPTY directory imports nothing and leaves a populated library alone") { dir =>
    val s = store(dir)
    LocalStorageMigration
      .migrate(directory("p1" -> "A"), _ => Some(payload("keep me")), s)
      .fold(e => fail(e), identity)

    val report = LocalStorageMigration.migrate("""{"projects":[]}""", _ => None, s).fold(e => fail(e), identity)
    assert(report.isEmpty)
    assertEquals(s.list().size, 1, "an empty directory emptied the library")
    assertEquals(s.list().head.text, "keep me")
  }

  tmp.test("unparseable directory JSON fails without touching the library") { dir =>
    val s = store(dir)
    LocalStorageMigration
      .migrate(directory("p1" -> "A"), _ => Some(payload("keep me")), s)
      .fold(e => fail(e), identity)

    assert(LocalStorageMigration.migrate("{ not json", _ => None, s).isLeft)
    assertEquals(s.list().size, 1)
    assertEquals(s.list().head.text, "keep me")
  }

  /** v1 could leave a directory entry whose payload was already gone. Recorded
    * as a failure rather than papered over with an empty diagram that would look
    * like the user's work vanished.
    */
  tmp.test("a directory entry with no payload is reported, not invented") { dir =>
    val s      = store(dir)
    val report = LocalStorageMigration
      .migrate(directory("p1" -> "A", "p2" -> "B"), id => Option.when(id == "p1")(payload("x")), s)
      .fold(e => fail(e), identity)

    assertEquals(report.imported.size, 1)
    assertEquals(report.failed.map(_._1), Vector("p2"))
    assertEquals(s.list().size, 1)
  }

  tmp.test("one bad payload does not stop the rest of the library importing") { dir =>
    val s      = store(dir)
    val report = LocalStorageMigration
      .migrate(
        directory("p1" -> "A", "bad" -> "B", "p3" -> "C"),
        id => Some(if id == "bad" then "{{{" else payload("ok")),
        s
      )
      .fold(e => fail(e), identity)

    assertEquals(report.imported.size, 2)
    assertEquals(report.failed.size, 1)
    assertEquals(s.list().size, 2)
  }

  tmp.test("a project id that is not filename-safe still lands in the diagrams directory") { dir =>
    val s = store(dir)
    LocalStorageMigration
      .migrate(directory("../../escape" -> "A"), _ => Some(payload("x")), s)
      .fold(e => fail(e), identity)
    assertEquals(s.list().size, 1)
    assertEquals(s.pathOf(s.list().head.id).getParent, dir.resolve("diagrams"))
  }

  tmp.test("a never-renamed project keeps the default name rather than an empty one") { dir =>
    val s = store(dir)
    LocalStorageMigration
      .migrate(directory("p1" -> ""), _ => Some(payload("x", name = "Untitled")), s)
      .fold(e => fail(e), identity)
    assertEquals(s.list().head.name, "Untitled")
  }
