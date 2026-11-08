package org.jpablo.graphexplorer.library

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.*
import org.jpablo.graphexplorer.viewer.models.{ArrowId, DiagramId, ElementIds, GroupId, NodeId}
import org.jpablo.graphexplorer.viewer.state.PersistedDiagramState

/** D7.3 makes the store the live state, so this mapping is not only a
  * convenience. It is the only protection against a UI save that loses the
  * data that the diagram has and the page does not have.
  */
class LibraryMappingSpec extends FunSuite:

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
    val (state, unparsed) = LibraryMapping.toPersisted(d)
    LibraryMapping.toDiagram(d.id, state, Some(d), unparsed, now)

  test("a diagram survives the round trip through the page"):
    val after = roundTrip(diagram)
    assertEquals(after.copy(updatedAt = diagram.updatedAt), diagram)

  test("the page's `source` is the diagram's `text`, which is the trap"):
    // PersistedDiagramState.source is the diagram TEXT, not its origin. Reading
    // it as provenance is the mistake the new model renamed the field to stop.
    val (state, _) = LibraryMapping.toPersisted(diagram)
    assertEquals(state.source, "digraph G { a -> b }")

  test("view state keeps the ElementRef spelling, so a node and a group cannot collide"):
    val state = PersistedDiagramState(
      hiddenElements = ElementIds(Set(NodeId("n1"), GroupId("n1"), ArrowId("e1"))),
      collapsedGroups = Set(GroupId("g1")),
      projectName = "P",
      source = "digraph G {}"
    )
    val d = LibraryMapping.toDiagram(DiagramId("x"), state, None, LibraryMapping.Unparsed.none, 1)
    assertEquals(d.metadata.hiddenElements, Set("node:n1", "group:n1", "arrow:e1"))
    assertEquals(d.metadata.collapsedGroups, Set("group:g1"))

  test("a UI save does not erase tags, notes, folder or binding"):
    // The page has no UI for these fields. If the code builds the diagram again
    // from the page only, it silently removes the result of `gx run tag`, the
    // binding from `gx import`, and the folder of the diagram.
    val (state, unparsed) = LibraryMapping.toPersisted(diagram)
    val edited = state.copy(source = "digraph G { a -> c }")
    val after  = LibraryMapping.toDiagram(diagram.id, edited, Some(diagram), unparsed, 99)
    assertEquals(after.text, "digraph G { a -> c }")
    assertEquals(after.metadata.tags, List("infra"))
    assertEquals(after.metadata.notes, "a note")
    assertEquals(after.metadata.folder, FolderPath.parse("/systems"))
    assertEquals(after.binding, diagram.binding)

  test("createdAt is the diagram's, updatedAt is now"):
    val after = roundTrip(diagram, now = 12345)
    assertEquals(after.createdAt, 1L)
    assertEquals(after.updatedAt, 12345L)

  test("a ref this build cannot parse is KEPT, not dropped"):
    // A newer `gx` wrote this diagram, and that `gx` knows a kind that this
    // build does not know. If `LibraryMapping` drops the ref, an older build that
    // opens the diagram deletes the ref on the next save. Then data is lost,
    // and no error shows.
    val forward = diagram.copy(metadata =
      diagram.metadata.copy(hiddenElements = Set("node:a", "sparkle:z"))
    )
    val (state, unparsed) = LibraryMapping.toPersisted(forward)
    assertEquals(state.hiddenElements.ids.size, 1, "only the known ref reaches the page")
    assertEquals(unparsed.hidden, Set("sparkle:z"))
    val after = LibraryMapping.toDiagram(forward.id, state, Some(forward), unparsed, 9)
    assertEquals(after.metadata.hiddenElements, Set("node:a", "sparkle:z"))

  test("a non-group in collapsedGroups is preserved rather than coerced"):
    val odd = diagram.copy(metadata = diagram.metadata.copy(collapsedGroups = Set("group:g1", "node:a")))
    val (state, unparsed) = LibraryMapping.toPersisted(odd)
    assertEquals(state.collapsedGroups, Set(GroupId("g1")))
    val after = LibraryMapping.toDiagram(odd.id, state, Some(odd), unparsed, 9)
    assertEquals(after.metadata.collapsedGroups, Set("group:g1", "node:a"))

  test("a format the diagram does not name reads as absent, and stays absent"):
    val unknown = diagram.copy(format = "")
    val (state, unparsed) = LibraryMapping.toPersisted(unknown)
    assertEquals(state.format, None)
    assertEquals(LibraryMapping.toDiagram(unknown.id, state, Some(unknown), unparsed, 9).format, "")

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
    val d = upickle.default.read[Diagram](realDiagram)
    val (state, unparsed) = LibraryMapping.toPersisted(d)
    assertEquals(state.projectName, "demo")
    assertEquals(state.source, "digraph Demo {\n  api -> db\n}\n")
    assertEquals(state.format, Some("DOT"))
    assert(unparsed.isEmpty, s"nothing should be unparseable: $unparsed")

  test("a headless `gx run hide` reaches the page as a hidden node"):
    // This is D7.3's whole claim, at the one point the viewer can check it:
    // view state written with no window open arrives as view state.
    val d = upickle.default.read[Diagram](realDiagram)
    val (state, _) = LibraryMapping.toPersisted(d)
    assertEquals(state.hiddenElements.ids, Set(NodeId("db")))

  test("saving it back does not unbind what gx bound"):
    val d = upickle.default.read[Diagram](realDiagram)
    val (state, unparsed) = LibraryMapping.toPersisted(d)
    val saved = LibraryMapping.toDiagram(d.id, state.copy(source = "digraph Demo {}"), Some(d), unparsed, 7)
    assertEquals(saved.binding, d.binding)
    assertEquals(saved.text, "digraph Demo {}")
    assertEquals(saved.metadata.hiddenElements, Set("node:db"))
