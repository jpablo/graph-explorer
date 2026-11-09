package org.jpablo.graphexplorer.library

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.backends.DiagramLanguages
import org.jpablo.graphexplorer.viewer.models.DiagramId
import org.jpablo.graphexplorer.viewer.state.DiagramSnapshot

/** What the home page and the library panel show about the library (D7.3).
  *
  * Two libraries exist, because the platforms are different. A browser has no
  * files, so the browser library ([[BrowserLibrary]], `localStorage`) keeps the
  * source of each diagram. On the desktop, a diagram is a file, and the
  * library ([[DesktopLibrary]]) is the set of metadata entries that the
  * desktop and `gx` share (ADR 0001).
  *
  * This interface is the part that the two have in common: the list, and what
  * a card shows. It is the one that `BrowserLibrary` already had. Thus the
  * callers use one set of names, and the `localStorage` path runs the same
  * code as before. `BrowserLibrary` satisfies this interface without a change.
  *
  * Every read is SYNCHRONOUS. The desktop's disk access is not, so it keeps an
  * in-memory mirror.
  */
trait LibraryListing:

  def index: Signal[LibraryIndex]

  def indexNow(): LibraryIndex

  /** The source of a diagram, for its thumbnail. */
  def diagramSource(id: DiagramId): Signal[String]

  def diagramCardInfo(id: DiagramId, languages: DiagramLanguages): Option[DiagramCardInfo]

  def diagramExists(id: DiagramId): Boolean

  /** Take the diagram out of the library. In the browser, the library is the
    * only copy, so this deletes the diagram. On the desktop, the file stays.
    */
  def deleteDiagram(id: DiagramId): Unit

/** A library that keeps the source of each diagram: the browser library.
  *
  * Only the web app has such a library. The desktop app keeps the source in
  * the file of each diagram.
  */
trait DiagramLibrary extends LibraryListing:

  def createDiagramPersistence(id: DiagramId, initialSource: Option[String]): Var[DiagramSnapshot]

  def createEmptyDiagram(name: String): DiagramId

  def createDiagram(name: String, source: String): DiagramId

  def findDiagramBySource(dot: String): Option[DiagramId]
