package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.EventStream
import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.gxcore.model.DiagramMetadata
import org.jpablo.graphexplorer.library.Library
import org.jpablo.graphexplorer.viewer.desktop.FilePersistence
import org.jpablo.graphexplorer.viewer.models.DiagramId

import scala.concurrent.{ExecutionContext, Future}

/** The place where the app stores the diagram of one viewer (§6).
  *
  * The type of the target selects the store, so a viewer holds a handle to ITS
  * store and cannot reach a different store. Before this, each viewer opened a
  * library handle with a `DiagramId` as the key. Thus a file had to give an id
  * that named no diagram. That made a library entry for a file that the person
  * only opened.
  */
trait DiagramPersistence:

  /** The state to open with. A snapshot: the value does not change under the
    * viewer while it restores.
    */
  def initial: DiagramSnapshot

  /** The metadata after a change from a DIFFERENT writer (D7.3).
    *
    * `initial` is a snapshot, and for a long time it was the only way back:
    * the viewer read the store once at mount and never again. Thus a `gx hide`
    * went into the store correctly, and did not show until the person opened
    * the view again.
    *
    * Only a file on the desktop has a second writer: `gx` changes the entry of
    * the file in the library. The browser library and an example have no
    * second writer, so their streams are empty. The SOURCE of a file does not
    * come through here: a file follows its SESSION (§7.3), which has the
    * conflict rule for a dirty editor.
    *
    * A value that the viewer already shows must cause no write. The viewer
    * adopts only a value that differs, and the store writes only a change.
    */
  def external: EventStream[DiagramMetadata]

  /** The state while the person edits it.
    *
    * A browser diagram saves here, on each change. A file does NOT save its
    * source here. §7.1 keeps an edit of a file in memory until a save, because
    * a write to a file can cause a conflict and can affect external tools. The
    * metadata of a file goes to its library entry at once.
    */
  def update(state: DiagramSnapshot): Unit

  /** Save now, because the person asked (§11). */
  def saveNow(state: DiagramSnapshot)(using ExecutionContext): Future[SaveResult]

  /** The message for the person, each time the store refuses a write that
    * [[update]] started. Only the browser library can refuse a write in this
    * way (a full `localStorage`). A file reports its failures through
    * [[saveNow]], so the other stores give an empty stream.
    */
  def refusedWrites: EventStream[String] = EventStream.empty

  /** Release what the store holds. Called when the view unmounts. */
  def close(): Unit

/** What a save did. §11 gives one rule per target, so the result must carry
  * more than a Boolean: a conflict is not a failure, and a target that cannot
  * be saved at all is neither.
  */
enum SaveResult derives CanEqual:
  case Saved
  case Conflict(message: String)
  case Failed(message: String)

  /** The target has nowhere to save to. An example must be copied to the
    * library first (§11), and it says so instead of failing silently.
    */
  case Unsupported(message: String)

object DiagramPersistence:

  /** The one place a target chooses a store.
    *
    * Total over [[ViewTarget]], and that totality is the guarantee §6 asks for:
    * a file cannot reach the browser library, because no branch here gives it
    * one. A new target must choose a store to compile.
    */
  def forTarget(target: ViewTarget, initialSource: Option[String]): DiagramPersistence =
    target match
      case ViewTarget.Diagram(id)      => LibraryPersistence(id, initialSource)
      case ViewTarget.File(session)    => FilePersistence(session, Library.desktop)
      case ViewTarget.Example(_, name) => EphemeralPersistence.example(initialSource, name)

/** The store for a diagram of the browser library (the web app).
  *
  * `Library.createDiagramPersistence` gives a `Var`, and each change to it goes
  * to storage. Thus `update` is a `set`, and this class opens and closes
  * nothing.
  *
  * `initialSource` is only for a diagram with no payload yet (a new diagram).
  * A stored payload has priority, also when a route gives a source.
  */
final class LibraryPersistence(id: DiagramId, initialSource: Option[String]) extends DiagramPersistence:

  private val state: Var[DiagramSnapshot] =
    Library.createDiagramPersistence(id, initialSource)

  lazy val initial: DiagramSnapshot = state.now()

  /** A browser library has no second writer, so nothing can change a diagram
    * behind an open tab.
    */
  def external: EventStream[DiagramMetadata] = EventStream.empty

  override def refusedWrites: EventStream[String] = Library.refusedWrites(id)

  def update(next: DiagramSnapshot): Unit = state.set(next)

  /** §11: a browser diagram already saves on each change, so ⌘S makes one
    * write at most. A change goes through the usual sync. With no change, ⌘S
    * writes again only when the storage refused the last write. The result is
    * `Failed` while the last write of the diagram is refused.
    */
  def saveNow(next: DiagramSnapshot)(using ExecutionContext): Future[SaveResult] =
    val unchanged = state.now() == next
    update(next)
    val refusal =
      if unchanged then Library.refusedWrite(id).flatMap(_ => Library.storeDiagram(id, next))
      else Library.refusedWrite(id)
    Future.successful(refusal.fold(SaveResult.Saved)(SaveResult.Failed(_)))

  /** This releases nothing. The storage handle belongs to the library, and it
    * lives longer than this view. A different view of the same diagram must
    * find it open.
    */
  def close(): Unit = ()

/** A diagram with no durable home.
  *
  * An example uses this.
  */
final class EphemeralPersistence(val initial: DiagramSnapshot, unsupportedMessage: String)
    extends DiagramPersistence:

  private var current = initial

  /** Nothing else can write here, so there is nothing to hear. */
  def external: EventStream[DiagramMetadata] = EventStream.empty

  def update(next: DiagramSnapshot): Unit = current = next

  def saveNow(next: DiagramSnapshot)(using ExecutionContext): Future[SaveResult] =
    update(next)
    Future.successful(SaveResult.Unsupported(unsupportedMessage))

  def close(): Unit = ()

  private[state] def latest: DiagramSnapshot = current

object EphemeralPersistence:

  /** The name that the gallery gives the example becomes the name of the
    * diagram. Without it, `displayTitle` shows "Untitled", because an example
    * has no stored name, and its source almost never declares a title.
    */
  def example(initialSource: Option[String], name: String): EphemeralPersistence =
    EphemeralPersistence(
      DiagramSnapshot.minimal(initialSource).copy(name = name),
      "Copy this example to your library before you save it"
    )
