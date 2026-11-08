package org.jpablo.graphexplorer.viewer.desktop

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.Signal
import org.jpablo.graphexplorer.gxcore.fs.LineEnding
import org.jpablo.graphexplorer.gxcore.model.*
import org.jpablo.graphexplorer.library.{BoundDiagram, Library}
import org.jpablo.graphexplorer.viewer.models.DiagramId
import org.scalajs.dom

import scala.concurrent.{ExecutionContext, Future}

/** An origin file changed, and a diagram in the library has a binding to it
  * (§8).
  *
  * A bound origin is NOT a loose file. The diagram is the authority for it
  * (§2). The binding tells which directions can move, and the three-hash
  * comparison decides. Before this, an origin event went to the viewer on the
  * screen and replaced its text. Thus a change from a file that the person
  * maybe did not look at went to a diagram that maybe had no binding to it.
  *
  * The decision comes from `gx-core`, so this page and `gx sync` get the same
  * answer. This object does only the I/O that the page can do. It asks the
  * shell for a hash, it asks the shell for a write, and it stores the result
  * in the diagram.
  */
object OriginReconciler:

  /** A diagram that needs a person, and the origin that it disagrees with
    * (§8).
    *
    * The snapshot stays with the state, because the resolution needs it.
    * "Take the file's version" adopts THIS text: the version that the strip
    * shows. It does not adopt the content of the file at the time of the click.
    * A second read at the time of the click adopts a version that nobody saw.
    *
    * `origin` is None when the page has no correct copy. An origin that is
    * not there has no text. A write that lost its compare-and-swap proves that the file
    * changed after the read. In the two cases, the strip reports the state and
    * does not offer a resolution. Before this, the strip did that for each
    * case.
    */
  final case class Unresolved(state: SyncState, origin: Option[OriginSnapshot]) derives CanEqual

  /** The state of each bound diagram against its origin, for the UI (§8).
    *
    * The map keeps only the states that need an action from a person. An
    * `InSync` diagram is the usual case and has nothing to report, so the code
    * REMOVES it and does not keep it. An entry here means "this needs you".
    */
  private val states: Var[Map[DiagramId, Unresolved]] = Var(Map.empty)

  def state(id: DiagramId): Signal[Option[SyncState]] =
    states.signal.map(_.get(id).map(_.state)).distinct

  /** What the strip shows, and what it can offer to do about it. */
  def unresolvedFor(id: DiagramId): Signal[Option[Unresolved]] =
    states.signal.map(_.get(id)).distinct

  def unresolved: Signal[Map[DiagramId, SyncState]] =
    states.signal.map(_.view.mapValues(_.state).toMap)

  private[graphexplorer] def reset(): Unit = states.set(Map.empty)

  /** Take the file's version (§8).
    *
    * The diagram adopts the text that the person saw, and the hash of the
    * origin becomes the new baseline. The diagram then reads `InSync`, so the
    * strip goes away. A viewer of this diagram follows through
    * `Persistence.followDiagram`. That method is the ONLY thing that makes
    * this true. For one day, this comment said so while the screen kept the
    * old text and only the strip changed.
    */
  def takeOrigin(id: DiagramId): Unit =
    resolve(id): origin =>
      Library.storeReconciliation(id, Some(origin.text), origin.hash)

  /** Keep the library's version (§8).
    *
    * Only the baseline moves, and the text of the diagram stays. The
    * comparison then reads `Ahead`: "I saw the file's version, and my version
    * stays". A binding that pushes then sends it on the next sync.
    *
    * The baseline must move for that. If the baseline stays, the base, the
    * local text and the remote text are all different. That is the definition
    * of `Diverged`. The strip then comes back immediately, and the decision of
    * the person changes nothing.
    */
  def keepDiagram(id: DiagramId): Unit =
    resolve(id): origin =>
      Library.storeReconciliation(id, None, origin.hash)

  /** Each resolution is one library write and then one forget.
    *
    * The forget is necessary. Nothing reconciles this diagram again until its
    * file changes. Thus an entry that stays asks the same question for the
    * rest of the session.
    */
  private def resolve(id: DiagramId)(write: OriginSnapshot => Unit): Unit =
    states
      .now()
      .get(id)
      .flatMap(_.origin)
      .foreach: origin =>
        write(origin)
        states.update(_ - id)

  /** Reconcile each diagram that has a binding to `path` against the text
    * that the shell read.
    *
    * @return
    *   the state that each bound diagram got. An empty list means that no
    *   diagram uses this file, so the file is a loose file.
    */
  def reconcile(path: String, text: String, revision: String)(using
      ExecutionContext
  ): Future[List[(DiagramId, SyncState)]] =
    Library.diagramsBoundTo(path) match
      case Nil      => Future.successful(Nil)
      case diagrams =>
        val origin = OriginSnapshot(text, ContentHash.fromHex(revision), LineEnding.detect(text))
        Future.traverse(diagrams)(reconcileOne(path, origin, _))

  private def reconcileOne(path: String, origin: OriginSnapshot, diagram: BoundDiagram)(using
      ExecutionContext
  ): Future[(DiagramId, SyncState)] =
    // The page cannot hash: `Hashing` is a JVM class. The convention comes from
    // `storedWith` rather than from a guess, because hashing the text any other
    // way makes every comparison below measure two different things.
    val stored = Reconciler.storedWith(Some(origin)).applyTo(diagram.text)

    DesktopIpc
      .hashText(stored)
      .flatMap: local =>
        val plan = Reconciler.planWith(Some(diagram.binding), diagram.text, Some(origin), local)
        perform(path, diagram, plan).map: state =>
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
          dom.console.warn(s"[origin] could not reconcile ${diagram.id.value}: ${error.getMessage}")
          (SyncState.Diverged, Some(origin))
      .map: (state, resolvable) =>
        remember(diagram.id, state, resolvable)
        (diagram.id, state)

  /** Carry out the plan, and report the state it actually reached.
    *
    * The RESULT, not `plan.state`. A push whose compare-and-swap loses ends
    * `Diverged` however the comparison started, and returning the planned state
    * instead used to erase the divergence the moment it was recorded: `perform`
    * marked it, and the caller then overwrote the mark with the settled state
    * it had predicted.
    */
  private def perform(path: String, diagram: BoundDiagram, plan: ReconcilePlan)(using
      ExecutionContext
  ): Future[SyncState] =
    plan.action match
      case ReconcileAction.DoNothing =>
        Future.successful(plan.state)

      case ReconcileAction.AdoptOrigin(text, base) =>
        // The diagram takes the text of the file. An open viewer follows
        // through `Persistence.followDiagram`, and not because a change went
        // to it directly.
        Library.storeReconciliation(diagram.id, Some(text), base)
        Future.successful(plan.state)

      case ReconcileAction.AdvanceBase(base) =>
        Library.storeReconciliation(diagram.id, None, base)
        Future.successful(plan.state)

      case ReconcileAction.WriteOrigin(text, expecting) =>
        // The shell's save is already a compare-and-swap on the hash, which is
        // the same protocol `Documents.write` uses from the command line.
        DesktopIpc
          .saveDocument(path, text, expecting.hex)
          .map:
            case DesktopIpc.SaveOutcome.Saved(_, revision) =>
              Library.storeReconciliation(diagram.id, None, ContentHash.fromHex(revision))
              plan.state
            case DesktopIpc.SaveOutcome.Conflict(_) =>
              // The file moved between the read and the write. Nothing is
              // written and nothing is lost; the next event reconciles again.
              SyncState.Diverged
            case other =>
              // The write failed for a different reason, so the text of the
              // diagram did not go to the file. `Ahead` already says that, and
              // the next event tries again.
              dom.console.warn(s"[origin] could not write $path: $other")
              plan.state

  /** Keep only what needs a person. §5.2's `needsUser` decides, so the UI and
    * the sync loop agree on which states are resting places.
    */
  private def remember(id: DiagramId, state: SyncState, origin: Option[OriginSnapshot]): Unit =
    if state.needsUser then states.update(_.updated(id, Unresolved(state, origin)))
    else states.update(_ - id)
