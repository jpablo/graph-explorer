package org.jpablo.graphexplorer.projects

import com.raquo.laminar.api.L.*
import io.laminext.syntax.core.storedString
import org.jpablo.graphexplorer.viewer.backends.{DiagramFormat, DiagramLanguages}
import org.jpablo.graphexplorer.viewer.state.{PersistedDiagramState, ProjectId, ViewerSettings}
import upickle.default.*

case class ProjectInfo(
    id:           ProjectId,
    name:         String,
    lastModified: Long,
    createdAt:    Long = System.currentTimeMillis()
) derives ReadWriter

case class ProjectsDirectory(projects: List[ProjectInfo] = Nil) derives ReadWriter

/** Presentation facts a library card needs from a project's payload. */
case class ProjectCardInfo(format: DiagramFormat, displayName: String)

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

  // laminext's storedString namespaces its keys with a "[StoredString]" prefix
  // (PersistenceSpec pins this); every raw localStorage access must go through rawKey
  // so the key format cannot drift between read, remove and scan sites.
  private def rawKey(key: String): String = s"[StoredString]$key"

  // One-shot read for probe-only lookups (no writes on these keys from here).
  private def readLocalStorage(key: String, default: => String): String =
    Option(dom.window.localStorage.getItem(rawKey(key))).getOrElse(default)

  /** Physically drop a key.
    *
    * Deletion must NOT go through `storedString(key, "").set("")`: laminext only syncs
    * an OBSERVED storedString to localStorage, so a throwaway instance writes nothing
    * and the payload outlives the project it belongs to. Those orphans then look like
    * a live library to `storedProjectPayloadsExist`.
    */
  private def removeLocalStorage(key: String): Unit =
    dom.window.localStorage.removeItem(rawKey(key))

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

  /** One-shot read of the persisted viewer settings — no new storage
    * subscription. For call sites that need current values at mount (the
    * library navbar's theme select) without allocating another synced Var:
    * pages persist through their own Var, so a long-lived Var from elsewhere
    * goes stale, and writing other settings through a stale copy would clobber
    * them.
    */
  def readViewerSettings(): ViewerSettings =
    try read[ViewerSettings](settingsStorageNow.now())
    catch
      case e: Throwable =>
        dom.console.error(s"Error reading viewer settings: $e")
        ViewerSettings.empty

  def createProjectDirectoryEntry(name: String): ProjectId =
    val now         = System.currentTimeMillis()
    val projectInfo = ProjectInfo(ProjectId.random, name, lastModified = now, createdAt = now)
    updateDirectory(_.modify(_.projects).using(projectInfo :: _))
    projectInfo.id

  def deleteProject(id: ProjectId): Unit =
    removeLocalStorage(projectKey(id)) // drop the project payload
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
    // GUARD against catastrophic index loss. The accident to prevent is a BAD READ:
    // `currentDir` coming back empty when the library is not (e.g. a wrong storage
    // key made it default to empty), so `updated` is empty too and writing it erases
    // the index while every payload survives.
    //
    // So the condition tests the READ too, not the write alone. A read that returned
    // entries is sound, and emptying it is then the caller's intent — deleting the last
    // project — which must go through: keying this off `updated` alone made that project
    // undeletable, since the library kept an entry no delete could remove. With
    // `currentDir` empty the skipped write is a no-op anyway (empty over empty).
    if currentDir.projects.isEmpty && updated.projects.isEmpty && storedProjectPayloadsExist() then
      if !phantomDirectoryReported then
        phantomDirectoryReported = true
        dom.console.warn(
          "The projects directory read as empty while project payloads exist in storage. " +
            "No write was needed, but if the library looks empty in the UI this is the bug to chase. " +
            "(Reported once per session.)"
        )
    else
      directoryStorage.set(write(updated))

  /** The condition above re-occurs on every state change while it holds; logging it
    * each time trains the reader to ignore it (it fired on every viewer test). */
  private var phantomDirectoryReported = false

  /** True when any project payload key holds substantive content. `deleteProject` removes
    * the key outright, but libraries written before that fix still hold orphaned payloads,
    * so this can report true for projects the directory no longer lists.
    */
  private def storedProjectPayloadsExist(): Boolean =
    val prefix = rawKey("graph-explorer.project.")
    (0 until dom.window.localStorage.length).exists { i =>
      Option(dom.window.localStorage.key(i)).exists { k =>
        k.startsWith(prefix) && Option(dom.window.localStorage.getItem(k)).exists(_.length > 2)
      }
    }

  private def projectKey(id: ProjectId): String =
    s"graph-explorer.project.${id.value}"

  /** The directory as currently persisted. */
  def directoryNow(): ProjectsDirectory =
    read[ProjectsDirectory](directoryStorageNow.now())

  /** Per-card presentation facts derived from a project's payload: the diagram format
    * (kind badge, kind filter) and the name to display. The display name is the stored
    * project name unless the project was never renamed, in which case the diagram's own
    * declared title substitutes (same rule as ViewerState.displayTitle in the detail view).
    *
    * Prefers the persisted format tag (authoritative — the user may have set it
    * explicitly); documents saved before the tag existed fall back to detection on
    * the source. A one-shot raw read: cheap enough to run for every card, and the
    * expensive part of the library (thumbnail rendering) stays lazy.
    */
  def projectCardInfo(id: ProjectId, languages: DiagramLanguages): Option[ProjectCardInfo] =
    try
      val state = read[PersistedDiagramState](readLocalStorage(projectKey(id), write(PersistedDiagramState.empty)))
      val format = state.format
        .flatMap(f => scala.util.Try(DiagramFormat.valueOf(f)).toOption)
        .getOrElse(DiagramFormat.detect(state.source))
      val name = state.projectName
      val displayName =
        if name.trim.nonEmpty && name != PersistedDiagramState.defaultProjectName then name
        else languages.forFormat(format).extractTitle(state.source).getOrElse(name)
      Some(ProjectCardInfo(format, displayName))
    catch case _: Throwable => None

  /** True when a project with this id exists in the directory. */
  def projectExists(id: ProjectId): Boolean =
    directoryNow().projects.exists(_.id == id)

  /** Find a project whose persisted source exactly matches the given DOT text. */
  def findProjectByExactSource(dot: String): Option[ProjectId] =
    val dir = directoryNow()
    dir.projects.collectFirst(Function.unlift { info =>
      try
        val state = read[PersistedDiagramState](readLocalStorage(projectKey(info.id), write(PersistedDiagramState.empty)))
        if state.source == dot then Some(info.id) else None
      catch
        case _: Throwable => None
    })

end ProjectStorage
