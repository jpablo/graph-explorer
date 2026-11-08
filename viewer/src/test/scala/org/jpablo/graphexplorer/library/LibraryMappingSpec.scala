package org.jpablo.graphexplorer.library

import com.raquo.airstream.ownership.Owner
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.unsafeWindowOwner
import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.*
import org.jpablo.graphexplorer.viewer.models.{ArrowId, DiagramId, ElementIds, GroupId, NodeId}
import org.jpablo.graphexplorer.viewer.state.{DiagramSnapshot, MetadataOps}

/** D7.3 makes the store the live state, so this mapping is not only a
  * convenience. It is the only protection against a UI save that loses the
  * data that the diagram has and the page does not have.
  */
class LibraryMappingSpec extends FunSuite:

  private given Owner = unsafeWindowOwner

  private val diagram = Diagram(
    id = DiagramId("d1"),
    format = "DOT",
    text = "digraph G { a -> b }",
    binding = Some(
      Binding(
        origin = OriginUri.parse("file:///tmp/a.dot").fold(e => throw IllegalArgumentException(e), identity),
        mode = SyncMode.Pull,
        baseHash = ContentHash.fromHex("abc"),
        lastSyncAt = 5
      )
    ),
    metadata = DiagramMetadata(
      name = "Architecture",
      folder = FolderPath.parse("/systems"),
      hiddenElements = Set("node:a", "arrow:e1"),
      collapsedGroups = Set("group:g1"),
      tags = List("infra"),
      notes = "a note",
      autoDetectFormat = Some(true)
    ),
    createdAt = 1,
    updatedAt = 2
  )

  private def roundTrip(d: Diagram, now: Long = 99): Diagram =
    LibraryMapping.mergeViewerSave(d.id, LibraryMapping.toSnapshot(d), Some(d), now)

  /** The snapshot of a viewer that opened `d` and made `edit` to its metadata. */
  private def viewerEdit(d: Diagram)(edit: MetadataOps => Unit): DiagramSnapshot =
    val opened = LibraryMapping.toSnapshot(d)
    val ops    = MetadataOps(Var(opened.metadata))
    edit(ops)
    opened.copy(metadata = ops.now())

  test("a diagram survives the round trip through the page"):
    val after = roundTrip(diagram)
    assertEquals(after.copy(updatedAt = diagram.updatedAt), diagram)

  test("the page's `source` is the diagram's `text`, which is the trap"):
    // DiagramSnapshot.source is the diagram TEXT, not its origin. Reading
    // it as provenance is the mistake the new model renamed the field to stop.
    assertEquals(LibraryMapping.toSnapshot(diagram).source, "digraph G { a -> b }")

  test("view state keeps the ElementRef spelling, so a node and a group cannot collide"):
    val snapshot = DiagramSnapshot.minimal(Some("digraph G {}"))
    val ops      = MetadataOps(Var(snapshot.metadata))
    ops.hiddenElements.set(ElementIds(Set(NodeId("n1"), GroupId("n1"), ArrowId("e1"))))
    ops.collapsedGroups.set(Set(GroupId("g1")))
    val d = LibraryMapping.mergeViewerSave(DiagramId("x"), snapshot.copy(metadata = ops.now()), None, 1)
    assertEquals(d.metadata.hiddenElements, Set("node:n1", "group:n1", "arrow:e1"))
    assertEquals(d.metadata.collapsedGroups, Set("group:g1"))

  test("a UI save does not erase tags, notes, folder or binding"):
    // The page has no UI for these fields. If the code builds the diagram again
    // from the page only, it silently removes the result of `gx run tag`, the
    // binding from `gx import`, and the folder of the diagram.
    val edited = LibraryMapping.toSnapshot(diagram).copy(source = "digraph G { a -> c }")
    val after  = LibraryMapping.mergeViewerSave(diagram.id, edited, Some(diagram), 99)
    assertEquals(after.text, "digraph G { a -> c }")
    assertEquals(after.metadata.tags, List("infra"))
    assertEquals(after.metadata.notes, "a note")
    assertEquals(after.metadata.folder, FolderPath.parse("/systems"))
    assertEquals(after.binding, diagram.binding)

  test("the stored diagram owns the folder, the tags and the notes, even when the snapshot holds others"):
    // The viewer adopts the whole metadata, so its snapshot holds a folder,
    // tags and notes. They are an old copy: the library owns them.
    val stale = LibraryMapping.toSnapshot(diagram)
    val now = diagram.copy(metadata =
      diagram.metadata.copy(folder = FolderPath.parse("/moved"), tags = List("new"), notes = "new note")
    )
    val after = LibraryMapping.mergeViewerSave(diagram.id, stale, Some(now), 99)
    assertEquals(after.metadata.folder, FolderPath.parse("/moved"))
    assertEquals(after.metadata.tags, List("new"))
    assertEquals(after.metadata.notes, "new note")

  test("the viewer owns the name, the hidden set, the collapsed set and autoDetectFormat"):
    val edited = viewerEdit(diagram): ops =>
      ops.name.set("Renamed")
      ops.hiddenElements.set(ElementIds(Set(NodeId("b"))))
      ops.collapsedGroups.set(Set(GroupId("g2")))
      ops.autoDetectFormat.set(false)
    val after = LibraryMapping.mergeViewerSave(diagram.id, edited, Some(diagram), 99)
    assertEquals(after.metadata.name, "Renamed")
    assertEquals(after.metadata.hiddenElements, Set("node:b"))
    assertEquals(after.metadata.collapsedGroups, Set("group:g2"))
    assertEquals(after.metadata.autoDetectFormat, Some(false))

  test("createdAt is the diagram's, updatedAt is now"):
    val after = roundTrip(diagram, now = 12345)
    assertEquals(after.createdAt, 1L)
    assertEquals(after.updatedAt, 12345L)

  test("a ref this build cannot parse survives a viewer edit of the hidden set"):
    // A newer `gx` wrote this diagram, and that `gx` knows a kind that this
    // build does not know. If the viewer drops the ref, an older build that
    // opens the diagram deletes the ref on the next save. Then data is lost,
    // and no error shows.
    val forward = diagram.copy(metadata = diagram.metadata.copy(hiddenElements = Set("node:a", "future:x")))
    val edited  = viewerEdit(forward)(_.hiddenElements.update(_ + NodeId("b")))
    val after   = LibraryMapping.mergeViewerSave(forward.id, edited, Some(forward), 9)
    assertEquals(after.metadata.hiddenElements, Set("node:a", "node:b", "future:x"))

  test("a non-group ref in collapsedGroups survives a collapse"):
    val odd    = diagram.copy(metadata = diagram.metadata.copy(collapsedGroups = Set("group:g1", "node:a")))
    val edited = viewerEdit(odd)(_.collapsedGroups.update(_ + GroupId("g2")))
    val after  = LibraryMapping.mergeViewerSave(odd.id, edited, Some(odd), 9)
    assertEquals(after.metadata.collapsedGroups, Set("group:g1", "group:g2", "node:a"))

  test("a format the diagram does not name reads as absent, and stays absent"):
    val unknown  = diagram.copy(format = "")
    val snapshot = LibraryMapping.toSnapshot(unknown)
    assertEquals(snapshot.format, None)
    assertEquals(LibraryMapping.mergeViewerSave(unknown.id, snapshot, Some(unknown), 9).format, "")

  test("autoDetectFormat survives, because it is a view setting that outlives a pull"):
    assertEquals(roundTrip(diagram).metadata.autoDetectFormat, Some(true))
    val off = diagram.copy(metadata = diagram.metadata.copy(autoDetectFormat = None))
    assertEquals(roundTrip(off).metadata.autoDetectFormat, None)

  // ------------------------------- what the real CLI actually writes

  /** A verbatim copy of `local-protocol/fixtures/library-record.json`, which
    * the real `gx` produced via `import` then `run demo hide`.
    *
    * Embedded rather than read, because these tests run in a browser with no
    * filesystem. `RealRecordSpec` on the JVM pins the FILE; this pins that the
    * viewer's mapping copes with the same shape — in particular a `metadata`
    * object carrying only the one key upickle bothered to write.
    */
  private lazy val realDiagram =
    """{
      |  "id": { "value": "demo" },
      |  "name": "demo",
      |  "folder": { "segments": [] },
      |  "format": "DOT",
      |  "text": "digraph Demo {\n  api -> db\n}\n",
      |  "binding": {
      |    "origin": "file:///private/tmp/gx-fixture/demo.dot",
      |    "mode": "Pull",
      |    "baseHash": "66e1007f7675a9596eb1338082c944eb6c724e8f23a968c49e73a62525b2010a",
      |    "lastSyncAt": 1787027585326
      |  },
      |  "metadata": { "hiddenElements": ["node:db"] },
      |  "createdAt": 1787027585327,
      |  "updatedAt": 1787027585606
      |}""".stripMargin

  test("the viewer can open a diagram `gx import` created"):
    val d        = upickle.default.read[Diagram](realDiagram)
    val snapshot = LibraryMapping.toSnapshot(d)
    assertEquals(snapshot.metadata.name, "demo")
    assertEquals(snapshot.source, "digraph Demo {\n  api -> db\n}\n")
    assertEquals(snapshot.format, Some("DOT"))
    assertEquals(snapshot.metadata, d.metadata, "the metadata reaches the viewer as it is")

  test("a headless `gx run hide` reaches the page as a hidden node"):
    // This is D7.3's whole claim, at the one point the viewer can check it:
    // view state written with no window open arrives as view state.
    val d = upickle.default.read[Diagram](realDiagram)
    assertEquals(MetadataOps.hiddenIn(LibraryMapping.toSnapshot(d).metadata).ids, Set(NodeId("db")))

  test("saving it back does not unbind what gx bound"):
    val d     = upickle.default.read[Diagram](realDiagram)
    val saved = LibraryMapping.mergeViewerSave(d.id, LibraryMapping.toSnapshot(d).copy(source = "digraph Demo {}"), Some(d), 7)
    assertEquals(saved.binding, d.binding)
    assertEquals(saved.text, "digraph Demo {}")
    assertEquals(saved.metadata.hiddenElements, Set("node:db"))
