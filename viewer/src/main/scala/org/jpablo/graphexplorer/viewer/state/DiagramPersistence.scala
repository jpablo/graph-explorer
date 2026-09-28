package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.EventStream
import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.projects.Library
import org.jpablo.graphexplorer.viewer.desktop.LooseFilePersistence

import scala.concurrent.{ExecutionContext, Future}

/** Where one viewer's diagram is stored (§6).
  *
  * Injected by target type, so a viewer holds a handle to ITS store and cannot
  * reach another. Before this, every viewer opened a library handle keyed by a
  * `ProjectId`, and a loose file therefore had to supply an id that named no
  * record — which stamped a library entry for a file the person only opened.
  */
trait DiagramPersistence:

  /** The state to open with. A snapshot: the value does not change under the
    * viewer while it restores.
    */
  def initial: PersistedDiagramState

  /** The state as somebody ELSE changed it (D7.3).
    *
    * `initial` is a snapshot, and for a long time it was the only way back in:
    * the viewer read the record once at mount and never looked again. The
    * library did its half — `createProjectPersistence` sets its `Var` when a
    * record changes underneath an open view — and nothing consumed it. So a
    * `gx set`, a `gx run hide`, or a pull from an origin file all landed in the
    * record correctly and stayed invisible until the view was reopened.
    *
    * Empty for a store nothing else can write. An example has no durable home,
    * and a loose file follows its SESSION instead (§7.3), which carries the
    * conflict rule a record does not need.
    *
    * A store must NOT emit this viewer's own [[update]] calls back. Comparing
    * the incoming text against what is on screen looks like it would do the
    * same job and does not: an update carries the snapshot taken when it was
    * scheduled, so a write made mid-transaction arrives holding text the editor
    * has already moved past. It reads as somebody else's change, and adopting
    * it undoes the person's last keystroke.
    */
  def external: EventStream[PersistedDiagramState]

  /** The state as the person edits it.
    *
    * A library record saves here, on every change. A loose file does NOT: §7.1
    * keeps a file edit in memory until a save, because a write to a file has
    * conflict and external-tool implications that a record does not have.
    */
  def update(state: PersistedDiagramState): Unit

  /** Save now, because the person asked (§11). */
  def saveNow(state: PersistedDiagramState)(using ExecutionContext): Future[SaveResult]

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
      case ViewTarget.LibraryDiagram(id)  => LibraryDiagramPersistence(id, initialSource)
      case ViewTarget.LooseFile(session)  => LooseFilePersistence(session)
      case ViewTarget.Example(_, name)    => EphemeralPersistence.example(initialSource, name)

/** A library record: the store the app always had.
  *
  * `Library.createProjectPersistence` returns a `Var` whose changes reach
  * storage, so `update` is a `set`, and there is nothing to open or close here.
  */
final class LibraryDiagramPersistence(id: ProjectId, initialSource: Option[String]) extends DiagramPersistence:

  private val state: Var[PersistedDiagramState] =
    Library.createProjectPersistence(id, initialSource)

  lazy val initial: PersistedDiagramState = state.now()

  /** Writes this view did not make.
    *
    * From the library's own stream, NOT from the `Var`. The `Var` carries this
    * view's writes back as well, and no filter on the value can separate them:
    * an echo holds the snapshot from when the write was scheduled, so it
    * arrives carrying text the editor has already moved past — which is exactly
    * what somebody else's change looks like.
    */
  def external: EventStream[PersistedDiagramState] = Library.recordChangedExternally(id)

  def update(next: PersistedDiagramState): Unit = state.set(next)

  /** §11: flush the record. A record saves on every change already, so ⌘S has
    * to force the write out rather than decide what to write. Any push to an
    * origin file is governed by the record's binding and sync mode, and Phase 3
    * moves that engine into `gx-core`.
    */
  def saveNow(next: PersistedDiagramState)(using ExecutionContext): Future[SaveResult] =
    update(next)
    Library.flush()
    Future.successful(SaveResult.Saved)

  /** Nothing to release. The storage handle belongs to the library, and it
    * outlives this view: another view of the same record must find it open.
    */
  def close(): Unit = ()

/** A diagram with no durable home.
  *
  * An example uses this. §7.2 also names it as the honest store for the parts
  * of a loose file that have nowhere to go.
  */
final class EphemeralPersistence(val initial: PersistedDiagramState, unsupportedMessage: String)
    extends DiagramPersistence:

  private var current = initial

  /** Nothing else can write here, so there is nothing to hear. */
  def external: EventStream[PersistedDiagramState] = EventStream.empty

  def update(next: PersistedDiagramState): Unit = current = next

  def saveNow(next: PersistedDiagramState)(using ExecutionContext): Future[SaveResult] =
    update(next)
    Future.successful(SaveResult.Unsupported(unsupportedMessage))

  def close(): Unit = ()

  private[state] def latest: PersistedDiagramState = current

object EphemeralPersistence:

  /** The gallery's name for the example becomes its project name. Without it
    * `displayTitle` falls through to "Untitled": an example has no stored name,
    * and its source rarely declares a title.
    */
  def example(initialSource: Option[String], name: String): EphemeralPersistence =
    EphemeralPersistence(
      PersistedDiagramState.minimal(initialSource).copy(projectName = name),
      "Copy this example to your library before you save it"
    )
