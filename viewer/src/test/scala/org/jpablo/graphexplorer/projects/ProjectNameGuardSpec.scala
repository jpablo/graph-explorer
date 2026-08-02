package org.jpablo.graphexplorer.projects

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.state.{PersistedDiagramState, ProjectId}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom
import upickle.default.read

/** A blank project name must never overwrite a real stored one — neither in the
  * project payload nor in the directory entry. The incident this pins
  * (2026-08-02): a stale incremental bundle crashed during TopLevel render
  * after the ViewerState ~> storage sync was already live, and the sync wrote
  * its pre-restore "" over the stored name — "Groups" became "" in both places
  * while the DOT source survived. Blank names have no legitimate writer
  * (RenameProjectDialog drops blank commits), so refusing them loses nothing.
  */
class ProjectNameGuardSpec extends FunSuite with TestHelpers:

  override def munitFixtures = List(mockStorageFixture())

  private def payloadKey(id: ProjectId): String =
    s"[StoredString]graph-explorer.project.${id.value}"

  private def storedPayload(id: ProjectId): PersistedDiagramState =
    read[PersistedDiagramState](dom.window.localStorage.getItem(payloadKey(id)))

  private def entryName(id: ProjectId): Option[String] =
    ProjectStorage.directoryNow().projects.find(_.id == id).map(_.name)

  /** Empty the library through the API, as DeleteProjectSpec does: the
    * directory storage handle is shared and outlives the per-test mock
    * storage, so leftovers from other suites must be drained, not assumed
    * away.
    */
  private def clearLibrary(): Unit =
    ProjectStorage.directoryNow().projects.foreach(p => ProjectStorage.deleteProject(p.id))

  test("a blank incoming name keeps the stored one, in the payload and the directory entry"):
    clearLibrary()
    val id    = ProjectStorage.createNamedProject("Groups", "digraph G {}")
    val state = ProjectStorage.createProjectPersistence(id, None)

    // The write a half-initialized viewer makes: name still pre-restore "", source live.
    state.set(state.now().copy(projectName = "", source = "digraph G { a }"))

    assertEquals(storedPayload(id).projectName, "Groups", "the payload keeps its name")
    assertEquals(entryName(id), Some("Groups"), "the directory entry keeps its name")
    assertEquals(storedPayload(id).source, "digraph G { a }", "the guard protects one field, not the whole write")

  test("a whitespace-only name counts as blank"):
    clearLibrary()
    val id    = ProjectStorage.createNamedProject("Groups", "digraph G {}")
    val state = ProjectStorage.createProjectPersistence(id, None)

    state.set(state.now().copy(projectName = "   "))

    assertEquals(storedPayload(id).projectName, "Groups")
    assertEquals(entryName(id), Some("Groups"))

  test("a real rename still reaches both the payload and the directory entry"):
    clearLibrary()
    val id    = ProjectStorage.createNamedProject("Old name", "digraph G {}")
    val state = ProjectStorage.createProjectPersistence(id, None)

    state.set(state.now().copy(projectName = "New name"))

    assertEquals(storedPayload(id).projectName, "New name")
    assertEquals(entryName(id), Some("New name"))

  test("a real name repairs a payload the accident already blanked"):
    clearLibrary()
    val id = ProjectStorage.createProjectDirectoryEntry("")
    dom.window.localStorage.setItem(payloadKey(id), """{"projectName":"","source":"digraph G {}"}""")
    val state = ProjectStorage.createProjectPersistence(id, None)

    state.set(state.now().copy(projectName = "Groups"))

    assertEquals(storedPayload(id).projectName, "Groups")
    assertEquals(entryName(id), Some("Groups"))
