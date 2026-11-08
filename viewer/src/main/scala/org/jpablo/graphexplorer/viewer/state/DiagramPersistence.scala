package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.EventStream
import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.library.Library
import org.jpablo.graphexplorer.viewer.desktop.LooseFilePersistence
import org.jpablo.graphexplorer.viewer.models.DiagramId

import scala.concurrent.{ExecutionContext, Future}

/** The place where the app stores the diagram of one viewer (§6).
  *
  * The type of the target selects the store, so a viewer holds a handle to ITS
  * store and cannot reach a different store. Before this, each viewer opened a
  * library handle with a `DiagramId` as the key. Thus a loose file had to give
  * an id that named no diagram. That made a library entry for a file that the
  * person only opened.
  */
trait DiagramPersistence:

  /** The state to open with. A snapshot: the value does not change under the
    * viewer while it restores.
    */
  def initial: DiagramSnapshot

  /** The state after a change from a DIFFERENT writer (D7.3).
    *
    * `initial` is a snapshot. For a long time, it was the only way back: the
    * viewer read the diagram once at mount and never again. The library did its
    * half: `createDiagramPersistence` sets its `Var` when a diagram changes
    * under an open view. But nothing used that value. Thus a `gx set`, a
    * `gx run hide`, or a pull from an origin file went into the diagram
    * correctly, and did not show until the person opened the view again.
    *
    * The stream is empty for a store that nothing else can write. An example
    * has no durable store. A loose file follows its SESSION (§7.3), which has
    * the conflict rule that a diagram does not need.
    *
    * A store must NOT send back the [[update]] calls of this viewer. A
    * comparison of the text that arrives with the text on the screen looks like a
    * solution, but it is not. An update holds the snapshot from the time of its
    * schedule. Thus a write during a transaction comes back with text that is
    * older than the editor. It looks like a change from a different writer, and
    * its adoption undoes the last keystroke of the person.
    */
  def external: EventStream[DiagramSnapshot]

  /** The state while the person edits it.
    *
    * A diagram saves here, on each change. A loose file does NOT. §7.1 keeps a
    * file edit in memory until a save, because a write to a file can cause a
    * conflict and can affect external tools. A diagram does not have these
    * problems.
    */
  def update(state: DiagramSnapshot): Unit

  /** Save now, because the person asked (§11). */
  def saveNow(state: DiagramSnapshot)(using ExecutionContext): Future[SaveResult]

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
    * a loose file cannot reach library persistence, because no branch here
    * gives it one. A new target must choose a store to compile.
    */
  def forTarget(target: ViewTarget, initialSource: Option[String]): DiagramPersistence =
    target match
      case ViewTarget.Diagram(id)        => LibraryPersistence(id, initialSource)
      case ViewTarget.LooseFile(session) => LooseFilePersistence(session)
      case ViewTarget.Example(_, name)   => EphemeralPersistence.example(initialSource, name)

/** The store for a diagram in the library. The app always had this store.
  *
  * `Library.createDiagramPersistence` gives a `Var`, and each change to it goes
  * to storage. Thus `update` is a `set`, and this class opens and closes
  * nothing.
  */
final class LibraryPersistence(id: DiagramId, initialSource: Option[String]) extends DiagramPersistence:

  private val state: Var[DiagramSnapshot] =
    Library.createDiagramPersistence(id, initialSource)

  lazy val initial: DiagramSnapshot = state.now()

  /** Writes this view did not make.
    *
    * From the library's own stream, NOT from the `Var`. The `Var` carries this
    * view's writes back as well, and no filter on the value can separate them:
    * an echo holds the snapshot from when the write was scheduled, so it
    * arrives carrying text the editor has already moved past — which is exactly
    * what somebody else's change looks like.
    */
  def external: EventStream[DiagramSnapshot] = Library.diagramChangedExternally(id)

  def update(next: DiagramSnapshot): Unit = state.set(next)

  /** §11: flush the diagram. A diagram already saves on each change, so ⌘S
    * must force the write out, and not decide what to write. The binding and
    * the sync mode of the diagram control a push to an origin file. Phase 3
    * moves that engine into `gx-core`.
    */
  def saveNow(next: DiagramSnapshot)(using ExecutionContext): Future[SaveResult] =
    update(next)
    Library.flush()
    Future.successful(SaveResult.Saved)

  /** This releases nothing. The storage handle belongs to the library, and it
    * lives longer than this view. A different view of the same diagram must
    * find it open.
    */
  def close(): Unit = ()

/** A diagram with no durable home.
  *
  * An example uses this. §7.2 also names it as the honest store for the parts
  * of a loose file that have nowhere to go.
  */
final class EphemeralPersistence(val initial: DiagramSnapshot, unsupportedMessage: String)
    extends DiagramPersistence:

  private var current = initial

  /** Nothing else can write here, so there is nothing to hear. */
  def external: EventStream[DiagramSnapshot] = EventStream.empty

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
      DiagramSnapshot.minimal(initialSource).modify(_.metadata.name).setTo(name),
      "Copy this example to your library before you save it"
    )
