package org.jpablo.graphexplorer.projects

import com.raquo.laminar.api.L.*
import io.laminext.syntax.core.storedString
import org.jpablo.graphexplorer.viewer.state.{PersistedDiagramState, ProjectId, ViewerSettings}
import upickle.default.*

case class ProjectInfo(
    id:           ProjectId,
    name:         String,
    lastModified: Long,
    createdAt:    Long = System.currentTimeMillis()
) derives ReadWriter

case class ProjectsDirectory(projects: List[ProjectInfo] = Nil) derives ReadWriter

object ProjectStorage:
  given owner: Owner = unsafeWindowOwner

  private val directoryStorage =
    storedString("graph-explorer.projects", write(ProjectsDirectory()))

  private val settingsStorage =
    storedString("graph-explorer.settings", write(ViewerSettings.empty))

  val directory: Signal[ProjectsDirectory] =
    directoryStorage.signal.map(read[ProjectsDirectory](_))

  /** Creates a reactive persistence layer for a project identified by the given `ProjectId`.
    *
    * This function initializes the state from local storage and sets up bidirectional synchronization
    * between the returned Var and storage. Any changes to the Var are automatically persisted,
    * and the project's directory entry is updated with the latest modification time and name.
    *
    * @param id
    *   The project's id.
    * @param initialSource
    *   Optional source to use if no persisted state exists.
    * @return
    *   A reactive `Var` containing the `PersistedDiagramState` with automatic persistence.
    */
  def createProjectPersistence(id: ProjectId, initialSource: Option[String]): Var[PersistedDiagramState] =
    val initialState   = PersistedDiagramState.minimal(initialSource)
    val projectStorage = storedString(projectKey(id), initial = write(initialState))
    // Initialize storage ~> PersistedDiagramState
    val persistedDiagramState: Var[PersistedDiagramState] =
      try
        Var(read[PersistedDiagramState](projectStorage.signal.observe.now()))
      catch
        case e: Throwable =>
          dom.console.error(s"Error reading state: $e, defaulting to initial state")
          Var(initialState)

    // synchronize PersistedDiagramState ~> storage
    persistedDiagramState.signal.distinct.changes.foreach: state =>
      // update project entry
      projectStorage.set(write(state))
      // update all directory fields
      updateDirectory: dir =>
        dir.modify(_.projects.eachWhere(_.id == id))
          .using(_.copy(lastModified = System.currentTimeMillis(), name = state.projectName))
    persistedDiagramState

  /** Retrieves the persisted viewer settings.
    *
    * This function initializes the settings from local storage. It ensures that any changes to the settings are persisted back to local
    * storage.
    *
    * @return
    *   A `Var` containing the `ViewerSettings`.
    */
  def loadViewerSettings(): Var[ViewerSettings] =
    // Initialize storage ~> ViewerSettings Var
    val viewerSettings =
      try
        Var(read[ViewerSettings](settingsStorage.signal.observe.now()))
      catch
        case e: Throwable =>
          dom.console.error(s"Error reading viewer settings: $e")
          Var(ViewerSettings.empty)
    // synchronize ViewerSettings Var ~> storage
    viewerSettings.signal.distinct.changes.foreach: settings =>
      settingsStorage.set(write(settings))
    viewerSettings

  def createProjectDirectoryEntry(name: String): ProjectId =
    val now         = System.currentTimeMillis()
    val projectInfo = ProjectInfo(ProjectId.random, name, lastModified = now, createdAt = now)
    updateDirectory(_.modify(_.projects).using(projectInfo :: _))
    projectInfo.id

  def deleteProject(id: ProjectId): Unit =
    storedString(projectKey(id), "").set("") // Clear the project data
    updateDirectory: dir =>
      dir.copy(projects = dir.projects.filterNot(_.id == id))

  /** Retrieves the content of a project identified by the given `ProjectId`.
    *
    * @param id
    *   The project's id.
    * @return
    *   A Signal containing the project's content as a String.
    */
  def getProjectContent(id: ProjectId): Signal[String] =
    val projectStorage = storedString(projectKey(id), write(PersistedDiagramState.empty))
    projectStorage.signal.map: stateStr =>
      try
        read[PersistedDiagramState](stateStr).source
      catch
        case e: Throwable =>
          dom.console.error(s"Error reading state: $e")
          "digraph G { b }"

  // ----------------- Private methods -----------------

  private def updateDirectory(f: ProjectsDirectory => ProjectsDirectory): Unit =
    val currentDir = read[ProjectsDirectory](directoryStorage.signal.observe.now())
    // Execute transformation immediately and store the result
    directoryStorage.set(write(f(currentDir)))

  private def projectKey(id: ProjectId): String =
    s"graph-explorer.project.${id.value}"

  /** Find a project whose persisted source exactly matches the given DOT text. */
  def findProjectByExactSource(dot: String): Option[ProjectId] =
    val dir = read[ProjectsDirectory](directoryStorage.signal.observe.now())
    dir.projects.collectFirst(Function.unlift { info =>
      val projectStorage = storedString(projectKey(info.id), write(PersistedDiagramState.empty))
      try
        val state = read[PersistedDiagramState](projectStorage.signal.observe.now())
        if state.source == dot then Some(info.id) else None
      catch
        case _: Throwable => None
    })

end ProjectStorage
