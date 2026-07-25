package org.jpablo.graphexplorer.projects

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.state.ProjectId
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

/** Deleting a project must remove BOTH halves of its persistence: the directory entry
  * and the payload. Two bugs made that untrue and fed each other —
  *
  *   - `deleteProject` cleared the payload through an unobserved laminext storedString,
  *     which never reaches localStorage, so every delete orphaned its payload;
  *   - the empty-directory guard in `updateDirectory` read those orphans as "the
  *     library is not really empty" and refused the write, so the LAST project could
  *     not be deleted at all — it reappeared in the library, silently.
  */
class DeleteProjectSpec extends FunSuite with TestHelpers:

  override def munitFixtures = List(mockStorageFixture())

  private def payloadKey(id: ProjectId): String =
    s"[StoredString]graph-explorer.project.${id.value}"

  private def payloadOf(id: ProjectId): Option[String] =
    Option(dom.window.localStorage.getItem(payloadKey(id)))

  /** Give the project the payload an opened project would have written. */
  private def writePayload(id: ProjectId): Unit =
    dom.window.localStorage.setItem(payloadKey(id), """{"projectName":"p","source":"digraph G {}"}""")

  /** Empty the library, so a project created next is genuinely the only one. */
  private def clearLibrary(): Unit =
    ProjectStorage.directoryNow().projects.foreach(p => ProjectStorage.deleteProject(p.id))

  test("deleting the LAST project removes its directory entry"):
    clearLibrary()
    val id = ProjectStorage.createProjectDirectoryEntry("only project")
    writePayload(id)
    assert(ProjectStorage.projectExists(id), "precondition: the project is in the directory")

    ProjectStorage.deleteProject(id)

    assert(
      !ProjectStorage.projectExists(id),
      "the last project must be deletable — the guard used to refuse this write, leaving it in the library forever"
    )
    assertEquals(ProjectStorage.directoryNow().projects, Nil, "the library is now legitimately empty")

  test("deleting a project removes its payload from storage"):
    clearLibrary()
    val id = ProjectStorage.createProjectDirectoryEntry("with payload")
    writePayload(id)
    assert(payloadOf(id).isDefined, "precondition: the payload is stored")

    ProjectStorage.deleteProject(id)

    assertEquals(payloadOf(id), None, "the payload must go with the project, not linger as an orphan")

  test("deleting one of several projects leaves the others intact"):
    clearLibrary()
    val keep = ProjectStorage.createProjectDirectoryEntry("keep")
    val drop = ProjectStorage.createProjectDirectoryEntry("drop")
    writePayload(keep)
    writePayload(drop)

    ProjectStorage.deleteProject(drop)

    assert(ProjectStorage.projectExists(keep), "the surviving project keeps its directory entry")
    assert(payloadOf(keep).isDefined, "the surviving project keeps its payload")
    assert(!ProjectStorage.projectExists(drop))
    assertEquals(payloadOf(drop), None)

  test("an orphaned payload does not block deleting the last project"):
    clearLibrary()
    // A payload with no directory entry — exactly what the old delete left behind, and
    // what the old guard mistook for a live library.
    dom.window.localStorage.setItem(payloadKey(ProjectId("orphan")), """{"projectName":"ghost","source":"digraph G {}"}""")

    val id = ProjectStorage.createProjectDirectoryEntry("last")
    writePayload(id)
    ProjectStorage.deleteProject(id)

    assert(!ProjectStorage.projectExists(id), "an unrelated orphan must not veto a legitimate delete")
    assertEquals(ProjectStorage.directoryNow().projects, Nil)
