package org.jpablo.graphexplorer.gxcore.store

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.*

/** The JSON shape of an old record on the disk, as the migration reads it.
  *
  * The old writer gave the two literals below (they were pinned in
  * `DiagramCodecSpec` while the old record type existed). The migration must
  * read each byte of these shapes, because the old records stay on the disk
  * and an older desktop app still writes them.
  */
class LegacyRecordSpec extends FunSuite:

  /** The old writer gave this for a bound record in a folder. */
  private val representativeJson =
    """{"id":{"value":"arch"},"name":"Architecture","folder":{"segments":["systems","core"]},"format":"DOT","text":"digraph G {\n  a -> b\n}\n","binding":{"origin":"file:///tmp/gx/arch.dot","mode":"Sync","baseHash":"ab12","lastSyncAt":1700000000000},"metadata":{"hiddenElements":["node:a"],"tags":["infra","draft"],"autoDetectFormat":true},"createdAt":1700000000001,"updatedAt":1700000000002}"""

  /** The old writer gave this for a record in the root folder, with no
    * binding and only default metadata. The root folder has a key, because the
    * old record had no default for `folder`.
    */
  private val minimalJson =
    """{"id":{"value":"arch"},"name":"Architecture","folder":{"segments":[]},"format":"DOT","text":"digraph G {\n  a -> b\n}\n","binding":null,"metadata":{},"createdAt":1700000000001,"updatedAt":1700000000002}"""

  private def read(json: String): LegacyRecord =
    LegacyRecord.read(json).fold(e => fail(e), identity)

  test("an old file reads back to the same record"):
    assertEquals(
      read(representativeJson),
      LegacyRecord(
        id = "arch",
        name = "Architecture",
        folder = FolderPath(List("systems", "core")),
        format = "DOT",
        text = "digraph G {\n  a -> b\n}\n",
        origin = Some("file:///tmp/gx/arch.dot"),
        metadata = DiagramMetadata(
          hiddenElements = Set("node:a"),
          tags = List("infra", "draft"),
          autoDetectFormat = Some(true)
        ),
        createdAt = 1700000000001L,
        updatedAt = 1700000000002L
      )
    )

  test("an old file gives the name and the folder to the record, and the defaults to the metadata"):
    val r = read(minimalJson)
    assertEquals(r.origin, None)
    assertEquals((r.name, r.folder), ("Architecture", FolderPath.root))
    assertEquals(
      r.metadata,
      DiagramMetadata(
        hiddenElements = Set.empty,
        collapsedGroups = Set.empty,
        tags = Nil,
        notes = "",
        autoDetectFormat = None
      )
    )

  test("an element ref that this build does not know is read as it is"):
    // A newer `gx` can write a kind of ref that this build cannot parse. The
    // reader keeps the string, so the migration does not erase it.
    val future = representativeJson.replace(""""hiddenElements":["node:a"]""", """"hiddenElements":["node:a","future:x"],"collapsedGroups":["cluster:y"]""")
    val r      = read(future)
    assertEquals(r.metadata.hiddenElements, Set("node:a", "future:x"))
    assertEquals(r.metadata.collapsedGroups, Set("cluster:y"))

  test("an origin that is not a file is read as it is"):
    val https = representativeJson.replace("file:///tmp/gx/arch.dot", "https://example.com/arch.dot")
    assertEquals(read(https).origin, Some("https://example.com/arch.dot"))

  test("a file that is not a record is refused with a reason"):
    for bad <- List("{ this is not a record", """{"id":{"value":"x"}}""", "[]") do
      assert(LegacyRecord.read(bad).isLeft, s"should have been refused: $bad")

  // ------------------------------------------------ OldRecords

  private def named(name: String, folder: List[String], id: String) =
    representativeJson
      .replace(""""name":"Architecture"""", s""""name":"$name"""")
      .replace(""""segments":["systems","core"]""", folder.map(f => s""""$f"""").mkString(""""segments":[""", ",", "]"))
      .replace(""""value":"arch"""", s""""value":"$id"""")

  test("the old records are in the order of the old library: folder, then name, then id"):
    val old = OldRecords.fromFiles(
      Vector(
        "c.json" -> Right(named("beta", Nil, "c")),
        "a.json" -> Right(named("Alpha", List("z"), "a")),
        "b.json" -> Right(named("alpha", Nil, "b")),
        "d.json" -> Right(named("Alpha", Nil, "a2"))
      )
    )
    assertEquals(old.records.map(_.id), Vector("a2", "b", "c", "a"))

  test("a file that cannot be read or parsed gives a line of the report, and the others stay"):
    val old = OldRecords.fromFiles(
      Vector(
        "good.json"   -> Right(minimalJson),
        "broken.json" -> Right("{ nope"),
        "denied.json" -> Left("permission denied")
      )
    )
    assertEquals(old.records.map(_.id), Vector("arch"))
    assertEquals(old.unreadable.size, 2, old.unreadable)
    assert(old.unreadable.exists(l => l.contains("diagrams/broken.json") && l.contains("unchanged")), old.unreadable)
    assert(old.unreadable.exists(l => l.contains("diagrams/denied.json") && l.contains("permission denied")), old.unreadable)

  test("a host sees the files that the records name, and the bases of their new file names"):
    val old = OldRecords.fromFiles(
      Vector(
        "a.json" -> Right(representativeJson),
        "b.json" -> Right(minimalJson.replace(""""value":"arch"""", """"value":"b"""").replace("Architecture", "a/b")),
        "w.json" -> Right(representativeJson.replace("file:///tmp/gx/arch.dot", "https://x/a.dot").replace(""""value":"arch"""", """"value":"w""""))
      )
    )
    assertEquals(old.filePaths, Vector("/tmp/gx/arch.dot"))
    assertEquals(old.fileNameBases.toSet, Set("Architecture", "a_b"))
