package org.jpablo.graphexplorer.projects

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.*
import org.jpablo.graphexplorer.viewer.models.{ArrowId, ElementIds, GroupId, NodeId}
import org.jpablo.graphexplorer.viewer.state.PersistedDiagramState

/** D7.3 makes the store the live state, so this mapping is not a convenience —
  * it is the only thing standing between a UI save and losing whatever the
  * record knew that the page does not.
  */
class LibraryMappingSpec extends FunSuite:

  private val record = Diagram(
    id = DiagramId("d1"),
    name = "Architecture",
    folder = FolderPath.parse("/systems"),
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

  test("a record survives the round trip through the page"):
    val after = roundTrip(record)
    assertEquals(after.copy(updatedAt = record.updatedAt), record)

  test("the page's `source` is the record's `text`, which is the trap"):
    // PersistedDiagramState.source is the diagram TEXT, not its origin. Reading
    // it as provenance is the mistake the new model renamed the field to stop.
    val (state, _) = LibraryMapping.toPersisted(record)
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
    // The page has no UI for any of these, so rebuilding a record from the page
    // alone would silently undo `gx run tag`, `gx import`'s binding, and any
    // folder the diagram was filed into.
    val (state, unparsed) = LibraryMapping.toPersisted(record)
    val edited = state.copy(source = "digraph G { a -> c }")
    val after  = LibraryMapping.toDiagram(record.id, edited, Some(record), unparsed, 99)
    assertEquals(after.text, "digraph G { a -> c }")
    assertEquals(after.metadata.tags, List("infra"))
    assertEquals(after.metadata.notes, "a note")
    assertEquals(after.folder, FolderPath.parse("/systems"))
    assertEquals(after.binding, record.binding)

  test("createdAt is the record's, updatedAt is now"):
    val after = roundTrip(record, now = 12345)
    assertEquals(after.createdAt, 1L)
    assertEquals(after.updatedAt, 12345L)

  test("a ref this build cannot parse is KEPT, not dropped"):
    // A record written by a newer `gx` that knows a kind this build does not.
    // Dropping it would mean opening the diagram in an older build deletes it
    // on the next save — data loss with no error anywhere.
    val forward = record.copy(metadata =
      record.metadata.copy(hiddenElements = Set("node:a", "sparkle:z"))
    )
    val (state, unparsed) = LibraryMapping.toPersisted(forward)
    assertEquals(state.hiddenElements.ids.size, 1, "only the known ref reaches the page")
    assertEquals(unparsed.hidden, Set("sparkle:z"))
    val after = LibraryMapping.toDiagram(forward.id, state, Some(forward), unparsed, 9)
    assertEquals(after.metadata.hiddenElements, Set("node:a", "sparkle:z"))

  test("a non-group in collapsedGroups is preserved rather than coerced"):
    val odd = record.copy(metadata = record.metadata.copy(collapsedGroups = Set("group:g1", "node:a")))
    val (state, unparsed) = LibraryMapping.toPersisted(odd)
    assertEquals(state.collapsedGroups, Set(GroupId("g1")))
    val after = LibraryMapping.toDiagram(odd.id, state, Some(odd), unparsed, 9)
    assertEquals(after.metadata.collapsedGroups, Set("group:g1", "node:a"))

  test("a format the record does not name reads as absent, and stays absent"):
    val unknown = record.copy(format = "")
    val (state, unparsed) = LibraryMapping.toPersisted(unknown)
    assertEquals(state.format, None)
    assertEquals(LibraryMapping.toDiagram(unknown.id, state, Some(unknown), unparsed, 9).format, "")

  test("autoDetectFormat survives, because it is a view setting that outlives a pull"):
    assertEquals(roundTrip(record).metadata.autoDetectFormat, Some(true))
    val off = record.copy(metadata = record.metadata.copy(autoDetectFormat = None))
    assertEquals(roundTrip(off).metadata.autoDetectFormat, None)
