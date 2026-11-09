package org.jpablo.graphexplorer.library

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.models.DiagramId
import org.jpablo.graphexplorer.viewer.state.DiagramSnapshot
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom
import upickle.default.read

/** A blank name must never overwrite a real stored name of a diagram, in the
  * payload or in the index entry.
  *
  * This spec pins an incident from 2026-08-02. A stale incremental bundle
  * crashed during the TopLevel render, after the ViewerState ~> storage sync
  * started. The sync wrote its "" from before the restore over the stored name.
  * "Groups" became "" in the two places, and the DOT source stayed. No correct
  * writer writes a blank name (RenameDiagramDialog ignores a blank name), so a
  * refusal loses nothing.
  */
class DiagramNameGuardSpec extends FunSuite with TestHelpers:

  override def munitFixtures = List(mockStorageFixture())

  private def payloadKey(id: DiagramId): String =
    s"[StoredString]graph-explorer.project.${id.value}"

  private def storedPayload(id: DiagramId): DiagramSnapshot =
    read[DiagramSnapshot](dom.window.localStorage.getItem(payloadKey(id)))

  private def entryName(id: DiagramId): Option[String] =
    BrowserLibrary.indexNow().diagrams.find(_.id == id).map(_.name)

  /** Empty the library through the API, as DeleteDiagramSpec does. All suites
    * share the storage handle of the index, and it lives longer than the mock
    * storage of one test. Thus this spec must remove the diagrams from other
    * suites, and must not assume that there are none.
    */
  private def clearLibrary(): Unit =
    BrowserLibrary.indexNow().diagrams.foreach(d => BrowserLibrary.deleteDiagram(d.id))

  test("a blank incoming name keeps the stored one, in the payload and the index entry"):
    clearLibrary()
    val id    = BrowserLibrary.createDiagram("Groups", "digraph G {}")
    val state = BrowserLibrary.createDiagramPersistence(id, None)

    // The write a half-initialized viewer makes: name still pre-restore "", source live.
    state.set(state.now().copy(name = "").copy(source = "digraph G { a }"))

    assertEquals(storedPayload(id).name, "Groups", "the payload keeps its name")
    assertEquals(entryName(id), Some("Groups"), "the index entry keeps its name")
    assertEquals(storedPayload(id).source, "digraph G { a }", "the guard protects one field, not the whole write")

  test("a whitespace-only name counts as blank"):
    clearLibrary()
    val id    = BrowserLibrary.createDiagram("Groups", "digraph G {}")
    val state = BrowserLibrary.createDiagramPersistence(id, None)

    state.set(state.now().copy(name = "   "))

    assertEquals(storedPayload(id).name, "Groups")
    assertEquals(entryName(id), Some("Groups"))

  test("a real rename still reaches both the payload and the index entry"):
    clearLibrary()
    val id    = BrowserLibrary.createDiagram("Old name", "digraph G {}")
    val state = BrowserLibrary.createDiagramPersistence(id, None)

    state.set(state.now().copy(name = "New name"))

    assertEquals(storedPayload(id).name, "New name")
    assertEquals(entryName(id), Some("New name"))

  test("a real name repairs a payload the accident already blanked"):
    clearLibrary()
    val id = BrowserLibrary.createEmptyDiagram("")
    dom.window.localStorage.setItem(payloadKey(id), """{"projectName":"","source":"digraph G {}"}""")
    val state = BrowserLibrary.createDiagramPersistence(id, None)

    state.set(state.now().copy(name = "Groups"))

    assertEquals(storedPayload(id).name, "Groups")
    assertEquals(entryName(id), Some("Groups"))
