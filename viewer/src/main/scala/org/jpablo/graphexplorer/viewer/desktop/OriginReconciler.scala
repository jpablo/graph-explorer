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

  /** A record that needs a person, and the origin it disagrees with (§8).
    *
    * The snapshot travels with the state because the resolution needs it. "Take
    * the file's version" adopts THIS text — the version the strip is about — and
    * not whatever the file holds at the moment the button is pressed. Re-reading
    * at click time would adopt a version nobody was shown.
    *
    * `origin` is None when the page holds no trustworthy copy: a missing origin
    * has no text at all, and a write that lost its compare-and-swap proves the
    * file moved after the read. Both report the situation without offering to
    * resolve it, which is what the strip did for every case before this.
    */
  final case class Unresolved(state: SyncState, origin: Option[OriginSnapshot]) derives CanEqual

  /** How each bound record stands against its origin, for the UI to show (§8).
    *
    * Kept for the states a person has to act on. An `InSync` record is the
    * normal case and has nothing to report, so it is REMOVED rather than
    * recorded — an entry here means "this needs you".
    */
  private val states: Var[Map[ProjectId, Unresolved]] = Var(Map.empty)

  def state(id: ProjectId): Signal[Option[SyncState]] =
    states.signal.map(_.get(id).map(_.state)).distinct

  /** What the strip shows, and what it can offer to do about it. */
  def unresolvedFor(id: ProjectId): Signal[Option[Unresolved]] =
    states.signal.map(_.get(id)).distinct

  def unresolved: Signal[Map[ProjectId, SyncState]] =
    states.signal.map(_.view.mapValues(_.state).toMap)

  private[graphexplorer] def reset(): Unit = states.set(Map.empty)

  /** Take the file's version (§8).
    *
    * The record adopts the text the person was shown, and the origin's hash
    * becomes the new baseline. The record then reads `InSync`, so the strip
    * goes. A viewer showing this record follows through
    * `Persistence.followRecord`, which is the ONLY thing that makes that true —
    * this comment asserted it for a day while the screen kept the old text and
    * only the strip changed.
    */
  def takeOrigin(id: ProjectId): Unit =
    resolve(id): origin =>
      Library.recordReconciled(id, Some(origin.text), origin.hash)

  /** Keep the library's version (§8).
    *
    * Only the baseline moves; the record's text stays. The comparison then
    * reads `Ahead` — "I have seen the file's version, and mine stands" — and a
    * binding that pushes carries it on the next sync.
    *
    * The baseline has to move for that. Leaving it where it was would leave
    * base, local and remote all different, which is the definition of
    * `Diverged`: the strip would come straight back, and the person's decision
    * would have changed nothing.
    */
  def keepRecord(id: ProjectId): Unit =
    resolve(id): origin =>
      Library.recordReconciled(id, None, origin.hash)

  /** Both resolutions are one library write and then one forget.
    *
    * The forget is not housekeeping. Nothing reconciles this record again until
    * its file changes, so an entry left behind would ask the same question for
    * the rest of the session.
    */
  private def resolve(id: ProjectId)(write: OriginSnapshot => Unit): Unit =
    states
      .now()
      .get(id)
      .flatMap(_.origin)
      .foreach: origin =>
        write(origin)
        states.update(_ - id)

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
        perform(path, record, plan).map: state =>
          // The snapshot becomes a RESOLUTION only when the reconciler itself
          // found the divergence. A write that lost its compare-and-swap also
          // reports Diverged, and there the file has moved since the read — so
          // the copy in hand is stale, and adopting it would write a version
          // nobody was shown.
          (state, Option.when(plan.state == SyncState.Diverged)(origin))
      .recover:
        case error =>
          // A hash the page could not obtain is not a state. Reporting one
          // would be inventing a comparison that never happened.
          dom.console.warn(s"[origin] could not reconcile ${record.id.value}: ${error.getMessage}")
          (SyncState.Diverged, Some(origin))
      .map: (state, resolvable) =>
        remember(record.id, state, resolvable)
        (record.id, state)

  /** Carry out the plan, and report the state it actually reached.
    *
    * The RESULT, not `plan.state`. A push whose compare-and-swap loses ends
    * `Diverged` however the comparison started, and returning the planned state
    * instead used to erase the divergence the moment it was recorded: `perform`
    * marked it, and the caller then overwrote the mark with the settled state
    * it had predicted.
    */
  private def perform(path: String, record: BoundRecord, plan: ReconcilePlan)(using
      ExecutionContext
  ): Future[SyncState] =
    plan.action match
      case ReconcileAction.DoNothing =>
        Future.successful(plan.state)

      case ReconcileAction.AdoptOrigin(text, base) =>
        // The record takes the file's text. An open viewer follows through
        // `Persistence.followRecord` — not because anything reached it directly.
        Library.recordReconciled(record.id, Some(text), base)
        Future.successful(plan.state)

      case ReconcileAction.AdvanceBase(base) =>
        Library.recordReconciled(record.id, None, base)
        Future.successful(plan.state)

      case ReconcileAction.WriteOrigin(text, expecting) =>
        // The shell's save is already a compare-and-swap on the hash, which is
        // the same protocol `Documents.write` uses from the command line.
        DesktopIpc
          .saveDocument(path, text, expecting.hex)
          .map:
            case DesktopIpc.SaveOutcome.Saved(_, revision) =>
              Library.recordReconciled(record.id, None, ContentHash.fromHex(revision))
              plan.state
            case DesktopIpc.SaveOutcome.Conflict(_) =>
              // The file moved between the read and the write. Nothing is
              // written and nothing is lost; the next event reconciles again.
              SyncState.Diverged
            case other =>
              // The write failed for some other reason, so the record's text
              // did not reach the file. That is what `Ahead` already says, and
              // the next event tries again.
              dom.console.warn(s"[origin] could not write $path: $other")
              plan.state

  /** Keep only what needs a person. §5.2's `needsUser` decides, so the UI and
    * the sync loop agree on which states are resting places.
    */
  private def remember(id: ProjectId, state: SyncState, origin: Option[OriginSnapshot]): Unit =
    if state.needsUser then states.update(_.updated(id, Unresolved(state, origin)))
    else states.update(_ - id)
