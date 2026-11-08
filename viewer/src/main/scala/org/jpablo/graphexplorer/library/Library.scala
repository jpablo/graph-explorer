package org.jpablo.graphexplorer.library

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.gxcore.model.ContentHash
import org.jpablo.graphexplorer.viewer.backends.DiagramLanguages
import org.jpablo.graphexplorer.viewer.models.DiagramId
import org.jpablo.graphexplorer.viewer.state.{PersistedDiagramState, ViewerSettings}

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

  private var backend: DiagramLibrary = BrowserLibrary

  /** Swap in the desktop's on-disk library. Called once, before the first
    * route renders, so nothing observes the browser library and then finds
    * itself pointed at a different one.
    */
  def install(library: DiagramLibrary): Unit = backend = library

  /** Put the browser library back. For tests: `install` is process-global, so
    * one test that swaps the backend would otherwise swap it for every test
    * that runs after it.
    */
  private[graphexplorer] def restoreDefault(): Unit = backend = BrowserLibrary

  def isDesktop: Boolean = backend ne BrowserLibrary

  def index: Signal[LibraryIndex] = backend.index
  def indexNow(): LibraryIndex    = backend.indexNow()

  def createDiagramPersistence(id: DiagramId, initialSource: Option[String]): Var[PersistedDiagramState] =
    backend.createDiagramPersistence(id, initialSource)

  def createEmptyDiagram(name: String): DiagramId = backend.createEmptyDiagram(name)
  def createDiagram(name: String, source: String): DiagramId = backend.createDiagram(name, source)
  def deleteDiagram(id: DiagramId): Unit = backend.deleteDiagram(id)
  def diagramSource(id: DiagramId): Signal[String] = backend.diagramSource(id)
  def diagramExists(id: DiagramId): Boolean = backend.diagramExists(id)

  /** The diagrams that have a binding to a file path (§8). Empty on the browser backend. */
  def diagramsBoundTo(path: String): List[BoundDiagram] = backend.diagramsBoundTo(path)

  /** The writes to a diagram from outside this view (D7.3). Empty on the browser backend. */
  def diagramChangedExternally(id: DiagramId): com.raquo.airstream.core.EventStream[PersistedDiagramState] =
    backend.diagramChangedExternally(id)

  /** The file that is the origin of a diagram (§8). None on the browser backend. */
  def originPathOf(id: DiagramId): Option[String] = backend.originPathOf(id)

  def storeReconciliation(id: DiagramId, text: Option[String], base: ContentHash): Unit =
    backend.storeReconciliation(id, text, base)
  def findDiagramBySource(dot: String): Option[DiagramId] = backend.findDiagramBySource(dot)

  def diagramCardInfo(id: DiagramId, languages: DiagramLanguages): Option[DiagramCardInfo] =
    backend.diagramCardInfo(id, languages)

  def flush(): Unit = backend.flush()

  /** Viewer settings stay in `localStorage` on BOTH backends.
    *
    * They are this window's preferences — theme, panel widths, sort order —
    * not library content, so there is nothing for `gx` to read or write and no
    * reason to make them a file two processes share.
    */
  def loadViewerSettings(): Var[ViewerSettings] = BrowserLibrary.loadViewerSettings()
  def readViewerSettings(): ViewerSettings      = BrowserLibrary.readViewerSettings()
