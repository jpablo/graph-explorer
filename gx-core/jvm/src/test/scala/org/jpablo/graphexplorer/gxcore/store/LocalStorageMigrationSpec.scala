package org.jpablo.graphexplorer.gxcore.store

import munit.FunSuite

/** The migration runs once, on all of the library of a user, and when it goes
  * wrong the result is not "a bad import" but "their work is gone". These tests
  * are mostly about what it must NOT do.
  *
  * The browser migration only reads: each browser diagram becomes an old
  * record with no origin, and the shared plan of [[MetadataMigration]] gives it
  * a file and an entry. The tests check the records, and then the plan.
  */
class LocalStorageMigrationSpec extends FunSuite:

  private val folder = "/home/me/Documents/Graph Explorer"

  private def directory(entries: (String, String)*): String =
    val diagrams = entries
      .map: (id, name) =>
        s"""{"id":{"value":"$id"},"name":"$name","lastModified":111,"createdAt":100}"""
      .mkString(",")
    s"""{"projects":[$diagrams]}"""

  private def payload(text: String, name: String = "Untitled"): String =
    s"""{
       |  "hiddenElements": {"ids": [{"$$type":"NodeId","value":"n1"}]},
       |  "collapsedGroups": [{"$$type":"GroupId","value":"g1"}],
       |  "projectName": "$name",
       |  "source": ${ujson.Str(text).render()},
       |  "format": "DOT"
       |}""".stripMargin

  private def read(directoryJson: String, payloadFor: String => Option[String]): OldRecords =
    LocalStorageMigration.read(directoryJson, payloadFor, now = () => 999L).fold(e => fail(e), identity)

  private def plan(old: OldRecords, existing: Map[String, String] = Map.empty): MetadataMigrationPlan =
    MetadataMigration.plan(
      old = old,
      readFile = existing.map((name, text) => MetadataMigration.pathIn(folder, name) -> text).get,
      diagramsFolder = folder,
      existingNames = existing.keySet,
      now = 5000L
    )

  // ------------------------------------------------------------- records

  test("a browser diagram becomes an old record with its text, its name, its format and its dates"):
    val r = read(directory("p1" -> "Architecture"), _ => Some(payload("digraph G { a }"))).records.head
    assertEquals(r.metadata.name, "Architecture")
    // The old `source` field held the TEXT. The new model calls that `text`,
    // and "source" or "origin" names the place that it came from.
    assertEquals(r.text, "digraph G { a }")
    assertEquals(r.format, "DOT")
    assertEquals(r.createdAt, 100L)
    assertEquals(r.updatedAt, 111L)
    assertEquals(r.id, LocalStorageMigration.idFor("p1").value)

  test("a browser diagram has no origin, because it never came from a file"):
    assertEquals(read(directory("p1" -> "A"), _ => Some(payload("x"))).records.head.origin, None)

  /** The migration READS the type tag. It does not skip it.
    *
    * Before, the migration discarded the tag. The result was a bare `n1`, and
    * it could not tell if the user hid a node or a group. Thus two different
    * elements with the same id became one entry, and when the user hid one of
    * them, the user hid both.
    */
  test("view state keeps the element KIND, so a node and a group cannot collide"):
    val meta = read(directory("p1" -> "A"), _ => Some(payload("x"))).records.head.metadata
    assertEquals(meta.hiddenElements, Set("node:n1"))
    assertEquals(meta.collapsedGroups, Set("group:g1"))

  /** `collapsedGroups` are groups by construction, so the kind is known even
    * when the old payload carried no tag at all.
    */
  test("an untagged collapsed group still gets its kind"):
    val untagged = """{"source":"digraph G {}","collapsedGroups":["g7"]}"""
    assertEquals(read(directory("p1" -> "A"), _ => Some(untagged)).records.head.metadata.collapsedGroups, Set("group:g7"))

  test("a payload in an unrecognised shape still yields the diagram, minus view state"):
    val odd = """{"source":"digraph G {}","hiddenElements":"who knows"}"""
    val r   = read(directory("p1" -> "A"), _ => Some(odd)).records.head
    assertEquals(r.text, "digraph G {}")
    assertEquals(r.metadata.hiddenElements, Set("who knows")) // best effort, never fatal

  test("a never-renamed browser diagram keeps the default name rather than an empty one"):
    assertEquals(read(directory("p1" -> ""), _ => Some(payload("x", name = "Untitled"))).records.head.metadata.name, "Untitled")

  // ------------------------------------------------------ never destroy

  test("an EMPTY directory gives no records"):
    assert(read("""{"projects":[]}""", _ => None).isEmpty)

  test("a directory that does not parse fails, so nothing is copied"):
    assert(LocalStorageMigration.read("{ not json", _ => None).isLeft)

  /** v1 could leave a directory entry whose payload was already gone. It is a
    * line of the report, not an empty diagram that looks like the work of the
    * user is gone.
    */
  test("a directory entry with no payload is reported, not invented"):
    val old = read(directory("p1" -> "A", "p2" -> "B"), id => Option.when(id == "p1")(payload("x")))
    assertEquals(old.records.map(_.metadata.name), Vector("A"))
    assertEquals(old.unreadable.size, 1)
    assert(old.unreadable.head.contains("[p2]"), old.unreadable)

  test("one bad payload does not stop the rest of the library"):
    val old = read(
      directory("p1" -> "A", "bad" -> "B", "p3" -> "C"),
      id => Some(if id == "bad" then "{{{" else payload("ok"))
    )
    assertEquals(old.records.map(_.metadata.name), Vector("A", "C"))
    assertEquals(old.unreadable.size, 1)
    assert(old.unreadable.head.contains("[bad]"), old.unreadable)

  // ------------------------------------------------------------- the plan

  test("each browser diagram becomes a new file in the folder for new diagrams, with an entry"):
    val old = read(directory("p1" -> "Architecture", "p2" -> "Notes"), id => Some(payload(s"digraph G { $id }")))
    val p   = plan(old)
    assertEquals(
      p.files,
      Vector(
        PlannedFile(s"$folder/Architecture.dot", "digraph G { p1 }"),
        PlannedFile(s"$folder/Notes.dot", "digraph G { p2 }")
      )
    )
    assertEquals(p.entries.map(_.path), Vector(s"$folder/Architecture.dot", s"$folder/Notes.dot"))
    assertEquals(p.entries.head.metadata.hiddenElements, Set("node:n1"))

  test("a second run uses the files of the first run, and makes no copies"):
    val old = read(directory("p1" -> "Architecture"), _ => Some(payload("digraph G { a }")))
    val p   = plan(old, existing = Map("Architecture.dot" -> "digraph G { a }"))
    assertEquals(p.files, Vector.empty)
    assertEquals(p.entries.map(_.path), Vector(s"$folder/Architecture.dot"))

  test("the plan never overwrites a file that exists, also a file that the person changed"):
    val old = read(directory("p1" -> "A"), _ => Some(payload("original")))
    val p   = plan(old, existing = Map("A.dot" -> "edited since"))
    assertEquals(p.files, Vector(PlannedFile(s"$folder/A 2.dot", "original")))

  test("a browser diagram whose name is not safe for a file system gets a file in the folder"):
    val p = plan(read(directory("p1" -> "../../escape"), _ => Some(payload("x"))))
    assert(p.files.forall(f => f.path.startsWith(s"$folder/") && !f.path.stripPrefix(s"$folder/").contains("/")), p.files)

  test("the default name gives Untitled.dot and no tag"):
    val p = plan(read(directory("p1" -> ""), _ => Some(payload("x", name = "Untitled"))))
    assertEquals(p.files.map(_.path), Vector(s"$folder/Untitled.dot"))
    assertEquals(p.entries.head.metadata.tags, Nil)
