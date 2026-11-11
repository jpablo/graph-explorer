package org.jpablo.graphexplorer.library

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.backends.DiagramLanguages
import org.jpablo.graphexplorer.viewer.models.DiagramId
import org.jpablo.graphexplorer.viewer.state.{DiagramSnapshot, ViewerSettings}

/** The library the app talks to, whichever one it is (D7.3).
  *
  * Call sites name this rather than a backend, so "where does the library
  * live" is answered once, at startup, instead of at each call site.
  *
  * The list (what the home page and the library panel show) comes from the
  * desktop library when one is installed, and else from `localStorage`. A
  * desktop that fails to give its own library keeps working on the browser one
  * rather than presenting an empty shelf.
  *
  * The operations on a stored source (`createDiagram`, `createDiagramPersistence`,
  * ...) are for the web app, so they always go to the browser library. On the
  * desktop, a diagram is a file, and [[desktop]] has the operations on files.
  */
object Library:

  private var desktopLibrary: Option[DesktopLibrary] = None

  /** Put in the desktop library. Called once, before the first route renders,
    * so nothing observes the browser library and then finds itself pointed at
    * a different one.
    */
  def install(library: DesktopLibrary): Unit = desktopLibrary = Some(library)

  /** Put the browser library back. For tests: `install` is process-global, so
    * one test that installs a library would otherwise install it for every
    * test that runs after it.
    */
  private[graphexplorer] def restoreDefault(): Unit =
    desktopLibrary.foreach(_.close())
    desktopLibrary = None
    notice.set(None)

  /** The desktop library, when the app runs in the desktop shell. */
  def desktop: Option[DesktopLibrary] = desktopLibrary

  def isDesktop: Boolean = desktopLibrary.isDefined

  private def listing: LibraryListing = desktopLibrary.getOrElse(BrowserLibrary)

  def index: Signal[LibraryIndex] = listing.index
  def indexNow(): LibraryIndex    = listing.indexNow()

  def diagramSource(id: DiagramId): Signal[String] = listing.diagramSource(id)
  def diagramExists(id: DiagramId): Boolean        = listing.diagramExists(id)

  /** Take the diagram out of the library. On the desktop, the file stays. */
  def deleteDiagram(id: DiagramId): Unit = listing.deleteDiagram(id)

  def diagramCardInfo(id: DiagramId, languages: DiagramLanguages): Option[DiagramCardInfo] =
    listing.diagramCardInfo(id, languages)

  // ------------------------------------------- the browser library (web app)

  def createDiagramPersistence(id: DiagramId, initialSource: Option[String])(using syncOwner: Owner): Var[DiagramSnapshot] =
    BrowserLibrary.createDiagramPersistence(id, initialSource)

  /** Write a diagram again. ⌘S uses this after a refused write. */
  def storeDiagram(id: DiagramId, state: DiagramSnapshot): Option[String] = BrowserLibrary.storeDiagram(id, state)
  def refusedWrite(id: DiagramId): Option[String]                         = BrowserLibrary.refusedWrite(id)
  def refusedWrites(id: DiagramId): EventStream[String]                   = BrowserLibrary.refusedWrites(id)

  def createEmptyDiagram(name: String): DiagramId            = BrowserLibrary.createEmptyDiagram(name)
  def createDiagram(name: String, source: String): DiagramId = BrowserLibrary.createDiagram(name, source)
  def findDiagramBySource(dot: String): Option[DiagramId]    = BrowserLibrary.findDiagramBySource(dot)

  // ------------------------------------------------------------- notices

  /** Lines that the home page shows to the person until the person closes
    * them: the report of a migration, or a failure of a library operation.
    */
  val notice: Var[Option[Vector[String]]] = Var(None)

  /** Show `lines` on the home page. A second notice goes below the first. */
  def showNotice(lines: Vector[String]): Unit =
    if lines.nonEmpty then notice.update(current => Some(current.getOrElse(Vector.empty) ++ lines))

  /** Viewer settings stay in `localStorage` on BOTH platforms.
    *
    * They are this window's preferences — theme, panel widths, sort order —
    * not library content, so there is nothing for `gx` to read or write and no
    * reason to make them a file two processes share.
    */
  def loadViewerSettings(): Var[ViewerSettings] = BrowserLibrary.loadViewerSettings()
  def readViewerSettings(): ViewerSettings      = BrowserLibrary.readViewerSettings()
