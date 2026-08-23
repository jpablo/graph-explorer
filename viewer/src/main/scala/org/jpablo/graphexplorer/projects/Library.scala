package org.jpablo.graphexplorer.projects

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.gxcore.model.ContentHash
import org.jpablo.graphexplorer.viewer.backends.DiagramLanguages
import org.jpablo.graphexplorer.viewer.state.{PersistedDiagramState, ProjectId, ViewerSettings}

/** The library the app talks to, whichever one it is (D7.3).
  *
  * Call sites name this rather than a backend, so "where does the library
  * live" is answered once, at startup, instead of nineteen times.
  *
  * Defaults to `localStorage`: the web has nothing else, and a desktop that
  * fails to hand over its own library keeps working on the browser one rather
  * than presenting an empty shelf.
  */
object Library:

  private var backend: DiagramLibrary = ProjectStorage

  /** Swap in the desktop's on-disk library. Called once, before the first
    * route renders, so nothing observes the browser library and then finds
    * itself pointed at a different one.
    */
  def install(library: DiagramLibrary): Unit = backend = library

  /** Put the browser library back. For tests: `install` is process-global, so
    * one test that swaps the backend would otherwise swap it for every test
    * that runs after it.
    */
  private[graphexplorer] def restoreDefault(): Unit = backend = ProjectStorage

  def isDesktop: Boolean = backend ne ProjectStorage

  def directory: Signal[ProjectsDirectory] = backend.directory
  def directoryNow(): ProjectsDirectory    = backend.directoryNow()

  def createProjectPersistence(id: ProjectId, initialSource: Option[String]): Var[PersistedDiagramState] =
    backend.createProjectPersistence(id, initialSource)

  def createProjectDirectoryEntry(name: String): ProjectId = backend.createProjectDirectoryEntry(name)
  def createNamedProject(name: String, source: String): ProjectId = backend.createNamedProject(name, source)
  def deleteProject(id: ProjectId): Unit = backend.deleteProject(id)
  def getProjectContent(id: ProjectId): Signal[String] = backend.getProjectContent(id)
  def projectExists(id: ProjectId): Boolean = backend.projectExists(id)

  /** The records bound to a file path (§8). Empty on the browser backend. */
  def recordsBoundTo(path: String): List[BoundRecord] = backend.recordsBoundTo(path)

  /** The file a record is bound to (§8). None on the browser backend. */
  def originPathOf(id: ProjectId): Option[String] = backend.originPathOf(id)

  def recordReconciled(id: ProjectId, text: Option[String], base: ContentHash): Unit =
    backend.recordReconciled(id, text, base)
  def findProjectByExactSource(dot: String): Option[ProjectId] = backend.findProjectByExactSource(dot)

  def projectCardInfo(id: ProjectId, languages: DiagramLanguages): Option[ProjectCardInfo] =
    backend.projectCardInfo(id, languages)

  def flush(): Unit = backend.flush()

  /** Viewer settings stay in `localStorage` on BOTH backends.
    *
    * They are this window's preferences — theme, panel widths, sort order —
    * not library content, so there is nothing for `gx` to read or write and no
    * reason to make them a file two processes share.
    */
  def loadViewerSettings(): Var[ViewerSettings] = ProjectStorage.loadViewerSettings()
  def readViewerSettings(): ViewerSettings      = ProjectStorage.readViewerSettings()
