package org.jpablo.graphexplorer.projects

import com.raquo.airstream.ownership.OneTimeOwner
import com.raquo.laminar.api.L.*
import io.laminext.syntax.core.storedString
import org.jpablo.graphexplorer.viewer.state.{PersistedState, ProjectId}
import upickle.default.*
import com.softwaremill.quicklens.*

case class ProjectInfo(
    id:           ProjectId,
    name:         String,
    lastModified: Long
) derives ReadWriter

case class ProjectsDirectory(projects: List[ProjectInfo] = Nil) derives ReadWriter

object ProjectStorage:
  private val directoryStorage = storedString(
    "graph-explorer.projects",
    write(ProjectsDirectory())
  )

  given owner: Owner = OneTimeOwner(() => ())

  // Public signal for observing directory changes
  val directory: Signal[ProjectsDirectory] =
    directoryStorage.signal.map(read[ProjectsDirectory](_))

  private def updateDirectory(f: ProjectsDirectory => ProjectsDirectory): Unit =
    val currentDir = read[ProjectsDirectory](directoryStorage.signal.observe.now())
    // Execute transformation immediately and store the result
    directoryStorage.set(write(f(currentDir)))

  /** Retrieves the persisted state of a project identified by the given `ProjectId`.
    *
    * This function initializes the state from the local storage. It ensures that any changes to the state are persisted
    * back to the local storage. Additionally, it updates the project's entry in the directory with the latest
    * modification time and project name.
    *
    * @param id
    *   The unique identifier of the project.
    * @return
    *   A `Var` containing the `PersistedState` of the project.
    */
  def loadProjectPersistedState(id: ProjectId): Var[PersistedState] =
    val initial = write(PersistedState.empty)
    val projectStorage = storedString(projectKey(id), initial)
    // Initialize storage ~> state
    val projectStateVar =
      try Var(read[PersistedState](projectStorage.signal.observe.now()))
      catch
        case e: Throwable =>
          dom.console.error(s"Error reading state: $e")
          Var(PersistedState.empty)
    // synchronize state ~> storage
    projectStateVar.signal.foreach: state =>
      // update project entry
      projectStorage.set(write(state))
      // update all directory fields
      updateDirectory: dir =>
        dir.modify(_.projects.eachWhere(_.id == id))
          .using(_.copy(lastModified = System.currentTimeMillis(), name = state.projectName))
    projectStateVar

  def createProject(name: String): ProjectId =
    val projectInfo = ProjectInfo(ProjectId.random, name, System.currentTimeMillis())
    updateDirectory(_.modify(_.projects).using(projectInfo :: _))
    projectInfo.id

  def deleteProject(id: ProjectId): Unit =
    storedString(projectKey(id), "").set("") // Clear the project data
    updateDirectory: dir =>
      dir.copy(projects = dir.projects.filterNot(_.id == id))

  private def projectKey(id: ProjectId): String =
    s"graph-explorer.project.${id.value}"

end ProjectStorage
