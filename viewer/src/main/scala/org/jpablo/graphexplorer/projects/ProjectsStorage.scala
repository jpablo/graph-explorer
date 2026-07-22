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

  private val DirectoryKey = "graph-explorer.projects"
  private val SettingsKey  = "graph-explorer.settings"

  private lazy val directoryStorage =
    storedString(DirectoryKey, write(ProjectsDirectory()))

  private lazy val settingsStorage =
    storedString(SettingsKey, write(ViewerSettings.empty))

  // ONE permanent observation per storage instance: observing the signal is what keeps
  // laminext's localStorage sync alive, and `.observe.now()` per call allocated a
  // permanent subscription each time (updateDirectory runs per keystroke, so these
  // accumulated by the thousands). Read via these handles instead.
  private lazy val directoryStorageNow = directoryStorage.signal.observe
  private lazy val settingsStorageNow  = settingsStorage.signal.observe

  // One-shot read for probe-only lookups (no writes on these keys from here).
  // NOTE: laminext's storedString namespaces its keys with a "[StoredString]" prefix
  // (PersistenceSpec pins this), so raw reads must use the same key format.
  private def readLocalStorage(key: String, default: => String): String =
    Option(dom.window.localStorage.getItem(s"[StoredString]$key")).getOrElse(default)

  lazy val directory: Signal[ProjectsDirectory] =
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
    // One observation per project storage: starts the localStorage sync and serves reads
    val projectStorageNow = projectStorage.signal.observe
    // Initialize storage ~> PersistedDiagramState
    val persistedDiagramState: Var[PersistedDiagramState] =
      try
        Var(read[PersistedDiagramState](projectStorageNow.now()))
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
        Var(read[ViewerSettings](settingsStorageNow.now()))
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
          PersistedDiagramState.minimalGraphText

  // ----------------- Private methods -----------------

  private def updateDirectory(f: ProjectsDirectory => ProjectsDirectory): Unit =
    val currentDir = read[ProjectsDirectory](directoryStorageNow.now())
    val updated    = f(currentDir)
    // GUARD against catastrophic index loss: writing an EMPTY directory while real
    // project payloads exist in storage means the read went wrong (e.g. a wrong
    // storage key made currentDir default to empty) — persisting it would erase the
    // whole library index while every project payload survives, which is exactly the
    // accident that once wiped the dev library. Skip the write and complain instead.
    if updated.projects.isEmpty && storedProjectPayloadsExist() then
      dom.console.error(
        "Refusing to overwrite the projects directory with an empty one while project payloads exist in storage — " +
          "this indicates a bug (bad directory read), not a legitimate empty library."
      )
    else
      directoryStorage.set(write(updated))

  /** True when any project payload key holds substantive content (deleted projects keep their key with an empty value). */
  private def storedProjectPayloadsExist(): Boolean =
    val prefix = s"[StoredString]graph-explorer.project."
    (0 until dom.window.localStorage.length).exists { i =>
      Option(dom.window.localStorage.key(i)).exists { k =>
        k.startsWith(prefix) && Option(dom.window.localStorage.getItem(k)).exists(_.length > 2)
      }
    }

  private def projectKey(id: ProjectId): String =
    s"graph-explorer.project.${id.value}"

  /** True when a project with this id exists in the directory. */
  def projectExists(id: ProjectId): Boolean =
    read[ProjectsDirectory](directoryStorageNow.now()).projects.exists(_.id == id)

  /** Find a project whose persisted source exactly matches the given DOT text. */
  def findProjectByExactSource(dot: String): Option[ProjectId] =
    val dir = read[ProjectsDirectory](directoryStorageNow.now())
    dir.projects.collectFirst(Function.unlift { info =>
      try
        val state = read[PersistedDiagramState](readLocalStorage(projectKey(info.id), write(PersistedDiagramState.empty)))
        if state.source == dot then Some(info.id) else None
      catch
        case _: Throwable => None
    })

end ProjectStorage
