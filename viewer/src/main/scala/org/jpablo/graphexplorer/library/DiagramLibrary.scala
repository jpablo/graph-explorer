package org.jpablo.graphexplorer.library

import com.raquo.airstream.core.EventStream
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.backends.DiagramLanguages
import org.jpablo.graphexplorer.gxcore.model.{Binding, ContentHash}
import org.jpablo.graphexplorer.viewer.models.DiagramId
import org.jpablo.graphexplorer.viewer.state.DiagramSnapshot

/** A diagram that has a binding to an origin file (§8).
  *
  * It holds the three things that reconciliation needs, and nothing more: the
  * diagram, the text to compare, and the binding. The binding tells which
  * directions can move, and what the two sides last agreed on. A whole
  * `Diagram` also holds metadata. Reconciliation must not read metadata,
  * because §5.3.1 keeps metadata out of the text comparison.
  */
final case class BoundDiagram(id: DiagramId, text: String, binding: Binding) derives CanEqual

/** The place where the library is (D7.3).
  *
  * Two implementations exist, because the platforms are different. A browser
  * has no disk, and the desktop shares its disk with `gx`. On the web, the
  * library is `localStorage`. On the desktop, the store on disk IS the live
  * state. Thus a diagram that `gx import` writes while no window is open is
  * already in the library that the UI reads.
  *
  * This interface is the one that `BrowserLibrary` already had. Thus the
  * callers use one set of names, and the `localStorage` path runs the same
  * code as before. A second implementation of that path could drift.
  * `BrowserLibrary` satisfies this interface without a change.
  *
  * Every read is SYNCHRONOUS. The desktop's disk access is not, so it keeps an
  * in-memory mirror; making the whole API async instead would have touched all
  * 19 call sites to buy nothing the mirror does not already give.
  */
trait DiagramLibrary:

  def index: Signal[LibraryIndex]

  def indexNow(): LibraryIndex

  def createDiagramPersistence(id: DiagramId, initialSource: Option[String]): Var[DiagramSnapshot]

  def createEmptyDiagram(name: String): DiagramId

  def createDiagram(name: String, source: String): DiagramId

  def deleteDiagram(id: DiagramId): Unit

  def diagramSource(id: DiagramId): Signal[String]

  def diagramCardInfo(id: DiagramId, languages: DiagramLanguages): Option[DiagramCardInfo]

  def diagramExists(id: DiagramId): Boolean

  def findDiagramBySource(dot: String): Option[DiagramId]

  /** The diagrams that have a binding to a file path (§8).
    *
    * The result is a LIST, not an Option. One file can be the origin of many
    * diagrams with different metadata. [[org.jpablo.graphexplorer.gxcore.model.OriginUri]]
    * gives this as the reason that the origin is an indexed attribute and not
    * the primary key. A change to an origin goes to each diagram that names it.
    *
    * `localStorage` gives Nil, and that is a correct answer. A browser library
    * has no file system, so it has no origin for a binding.
    */
  def diagramsBoundTo(path: String): List[BoundDiagram] = Nil

  /** The diagram after a change from a different writer (D7.3).
    *
    * Only an OUTSIDE write sends a value on this stream.
    * `createDiagramPersistence` gives a `Var` that this view and the library
    * both write to. A consumer of that `Var` cannot tell the two writers apart.
    * An echo of a write from this view holds the snapshot from the time of the
    * write. Thus the echo holds text that is older than the editor, and it
    * looks the same as a change from a different writer.
    *
    * The stream is empty on `localStorage`, and that is correct. A browser
    * library has no second writer, so nothing can change a diagram behind an
    * open tab.
    */
  def diagramChangedExternally(id: DiagramId): EventStream[DiagramSnapshot] = EventStream.empty

  /** The file that is the origin of this diagram, if it has one (§8).
    *
    * This is the opposite direction from [[diagramsBoundTo]], for a different
    * moment. That method answers "a file changed: which diagrams use it?".
    * This method answers "a diagram opens: which file must the app listen
    * to?". One answer does not come cheaply from the other. That method uses
    * an index of groups. This method is one lookup, and it must not make an
    * index.
    *
    * `localStorage` gives None, because a browser library has no file system.
    */
  def originPathOf(id: DiagramId): Option[String] = None

  /** Store what reconciliation decided (§8).
    *
    * @param text
    *   the origin's text, when a pull adopted it. None when only the agreed
    *   baseline moved, which is the `Converged` case: the content already
    *   matches, so rewriting it would be a change with no difference.
    * @param base
    *   the hash both sides now agree on.
    */
  def storeReconciliation(id: DiagramId, text: Option[String], base: ContentHash): Unit = ()

  /** Push anything still held by a debounce.
    *
    * `localStorage` writes on the spot and has nothing to flush; the desktop
    * batches, because a file write per keystroke means rewriting the whole
    * diagram through an IPC hop. That batching is also a way to lose the last
    * edits, so quitting or losing focus has to be able to force it.
    */
  def flush(): Unit = ()
