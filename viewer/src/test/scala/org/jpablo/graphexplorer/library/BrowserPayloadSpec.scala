package org.jpablo.graphexplorer.library

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.DiagramMetadata
import org.jpablo.graphexplorer.viewer.state.DiagramSnapshot
import upickle.default.{read, write}

/** The JSON of a diagram in the browser library (`localStorage`) must not change.
  *
  * Each browser holds payloads in this shape, and `LocalStorageMigration` reads
  * it. The literal below is the output of the code before `DiagramSnapshot`:
  * the old `PersistedDiagramState` wrote it for the same diagram. Thus the
  * literal is the contract, and the new codec must agree with it byte for byte.
  */
class BrowserPayloadSpec extends FunSuite:

  /** Written by `PersistedDiagramState` with these values:
    * `hiddenElements = ElementIds(Set(NodeId("a"), ArrowId("e1"), GroupId("g")))`,
    * `collapsedGroups = Set(GroupId("g1"), GroupId("g2"))`, `projectName = "Pinned"`,
    * the source below, `format = Some("DOT")`, `autoDetectFormat = Some(false)`.
    */
  private val pinned =
    """{"hiddenElements":[{"$type":"NodeId","value":"a"},{"$type":"ArrowId","value":"e1"},{"$type":"GroupId","value":"g"}],"collapsedGroups":["g1","g2"],"projectName":"Pinned","source":"digraph G {\n  a -> b\n}","format":"DOT","autoDetectFormat":false}"""

  private val representative = DiagramSnapshot(
    source = "digraph G {\n  a -> b\n}",
    format = Some("DOT"),
    metadata = DiagramMetadata(
      hiddenElements = Set("node:a", "arrow:e1", "group:g"),
      collapsedGroups = Set("group:g1", "group:g2"),
      autoDetectFormat = Some(false)
    ),
    name = "Pinned"
  )

  test("the codec writes the JSON that the browser library has always stored"):
    assertEquals(write(representative), pinned)

  test("the codec reads that JSON back"):
    assertEquals(read[DiagramSnapshot](pinned), representative)

  test("an empty diagram writes only the fields that differ from their defaults"):
    // Also the output of the old code, for `PersistedDiagramState.empty`.
    assertEquals(write(DiagramSnapshot.empty), """{"projectName":"Untitled","source":"digraph G {\n}"}""")

  test("an old payload with missing fields reads them as their defaults"):
    val old = read[DiagramSnapshot]("""{"projectName":"Old","source":"digraph G {}"}""")
    assertEquals(old, DiagramSnapshot("digraph G {}", None, DiagramMetadata(), name = "Old"))
    assertEquals(old.metadata.autoDetectFormat, None, "None, which the viewer reads as on")

  test("an empty payload reads as an unnamed, empty diagram"):
    assertEquals(read[DiagramSnapshot]("{}"), DiagramSnapshot("", None, DiagramMetadata(), name = ""))

  test("the browser library stores no tags or notes, and does not invent them"):
    val withLibraryFields = representative.copy(metadata = representative.metadata.copy(tags = List("t"), notes = "n"))
    assertEquals(write(withLibraryFields), pinned, "the stored form has no field for them")
    val back = read[DiagramSnapshot](pinned).metadata
    assertEquals((back.tags, back.notes), (Nil, ""))

  test("the stored name of a browser diagram is the projectName field"):
    assertEquals(write(representative.copy(name = "Renamed")), pinned.replace("\"Pinned\"", "\"Renamed\""))
