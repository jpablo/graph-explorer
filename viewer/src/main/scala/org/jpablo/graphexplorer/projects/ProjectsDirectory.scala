package org.jpablo.graphexplorer.projects

import com.raquo.airstream.ownership.OneTimeOwner
import com.raquo.laminar.api.L.*
import io.laminext.syntax.core.storedString
import org.jpablo.graphexplorer.viewer.state.{PersistedState, ProjectId}
import org.scalajs.dom
import upickle.default.*

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
    directoryStorage.update(current => write(f(read[ProjectsDirectory](current))))

  def projectPersistedState(id: ProjectId): Var[PersistedState] =
    val storage = storedString(projectKey(id), write(PersistedState.empty))
    val stateVar =
      try Var(read[PersistedState](storage.signal.observe.now()))
      catch
        case e: Throwable =>
          dom.console.error(s"Error reading state: $e")
          Var(PersistedState.empty)
    // Set up persistence of state changes
    stateVar.signal.foreach: state =>
      storage.set(write(state))
      updateLastModified(id)

    stateVar

  def createProject(name: String): ProjectId =
    val id = ProjectId.random
    val projectInfo = ProjectInfo(id, name, System.currentTimeMillis())
    updateDirectory(dir => dir.copy(projects = projectInfo :: dir.projects))
    id

  def deleteProject(id: ProjectId): Unit =
    storedString(projectKey(id), "").set("") // Clear the project data
    updateDirectory: dir =>
      dir.copy(projects = dir.projects.filterNot(_.id == id))

  private def updateLastModified(id: ProjectId): Unit =
    updateDirectory: dir =>
      dir.copy(projects = dir.projects.map:
        case p if p.id == id => p.copy(lastModified = System.currentTimeMillis())
        case p               => p
      )

  private def projectKey(id: ProjectId): String =
    s"graph-explorer.project.${id.value}"

end ProjectStorage
