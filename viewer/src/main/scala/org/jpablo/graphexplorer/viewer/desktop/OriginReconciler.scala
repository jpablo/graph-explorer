package org.jpablo.graphexplorer.viewer.desktop

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.Signal
import org.jpablo.graphexplorer.gxcore.fs.LineEnding
import org.jpablo.graphexplorer.gxcore.model.*
import org.jpablo.graphexplorer.projects.{BoundRecord, Library}
import org.jpablo.graphexplorer.viewer.state.ProjectId
import org.scalajs.dom

import scala.concurrent.{ExecutionContext, Future}

/** An origin file changed, and some library record is bound to it (§8).
  *
  * A bound origin is NOT a loose file. The record is authoritative for it (§2),
  * the binding says which directions may move, and the three-hash comparison
  * decides. Before this, an origin event reached whichever viewer was on
  * screen and replaced its text — a change from a file the person may not have
  * been looking at, applied to a record that may not have been bound to it.
  *
  * The decision comes from `gx-core`, so this page and `gx sync` reach the same
  * answer. What is left here is the I/O the page can do: ask the shell to hash,
  * ask the shell to write, and store the result in the record.
  */
object OriginReconciler:

  /** How each bound record stands against its origin, for the UI to show (§8).
    *
    * Kept for the states a person has to act on. An `InSync` record is the
    * normal case and has nothing to report, so it is REMOVED rather than
    * recorded — an entry here means "this needs you".
    */
  private val states: Var[Map[ProjectId, SyncState]] = Var(Map.empty)

  def state(id: ProjectId): Signal[Option[SyncState]] =
    states.signal.map(_.get(id)).distinct

  def unresolved: Signal[Map[ProjectId, SyncState]] = states.signal

  private[graphexplorer] def reset(): Unit = states.set(Map.empty)

  /** Reconcile every record bound to `path` against what the shell just read.
    *
    * @return
    *   the state each bound record reached. Empty means no record claims this
    *   file, which is what makes it a loose document instead.
    */
  def reconcile(path: String, text: String, revision: String)(using
      ExecutionContext
  ): Future[List[(ProjectId, SyncState)]] =
    Library.recordsBoundTo(path) match
      case Nil     => Future.successful(Nil)
      case records =>
        val origin = OriginSnapshot(text, ContentHash.fromHex(revision), LineEnding.detect(text))
        Future.traverse(records)(reconcileOne(path, origin, _))

  private def reconcileOne(path: String, origin: OriginSnapshot, record: BoundRecord)(using
      ExecutionContext
  ): Future[(ProjectId, SyncState)] =
    // The page cannot hash: `Hashing` is a JVM class. The convention comes from
    // `storedWith` rather than from a guess, because hashing the text any other
    // way makes every comparison below measure two different things.
    val stored = Reconciler.storedWith(Some(origin)).applyTo(record.text)

    DesktopIpc
      .hashText(stored)
      .flatMap: local =>
        val plan = Reconciler.planWith(Some(record.binding), record.text, Some(origin), local)
        perform(path, record, plan).map(_ => record.id -> plan.state)
      .recover:
        case error =>
          // A hash the page could not obtain is not a state. Reporting one
          // would be inventing a comparison that never happened.
          dom.console.warn(s"[origin] could not reconcile ${record.id.value}: ${error.getMessage}")
          record.id -> SyncState.Diverged
      .map: (id, state) =>
        remember(id, state)
        (id, state)

  private def perform(path: String, record: BoundRecord, plan: ReconcilePlan)(using
      ExecutionContext
  ): Future[Unit] =
    plan.action match
      case ReconcileAction.DoNothing =>
        Future.unit

      case ReconcileAction.AdoptOrigin(text, base) =>
        // The record takes the file's text. An open viewer follows, because it
        // watches the record — not because anything reached it directly.
        Library.recordReconciled(record.id, Some(text), base)
        Future.unit

      case ReconcileAction.AdvanceBase(base) =>
        Library.recordReconciled(record.id, None, base)
        Future.unit

      case ReconcileAction.WriteOrigin(text, expecting) =>
        // The shell's save is already a compare-and-swap on the hash, which is
        // the same protocol `Documents.write` uses from the command line.
        DesktopIpc
          .saveDocument(path, text, expecting.hex)
          .map:
            case DesktopIpc.SaveOutcome.Saved(_, revision) =>
              Library.recordReconciled(record.id, None, ContentHash.fromHex(revision))
            case DesktopIpc.SaveOutcome.Conflict(_) =>
              // The file moved between the read and the write. Nothing is
              // written and nothing is lost; the next event reconciles again.
              remember(record.id, SyncState.Diverged)
            case other =>
              dom.console.warn(s"[origin] could not write $path: $other")

  /** Keep only what needs a person. §5.2's `needsUser` decides, so the UI and
    * the sync loop agree on which states are resting places.
    */
  private def remember(id: ProjectId, state: SyncState): Unit =
    if state.needsUser then states.update(_.updated(id, state))
    else states.update(_ - id)
