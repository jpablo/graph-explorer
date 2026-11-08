package org.jpablo.graphexplorer.gxcore.model

import org.jpablo.graphexplorer.gxcore.fs.LineEnding

/** The origin as the caller just observed it.
  *
  * `hash` is of the BYTES on disk, and `lineEnding` is the convention those
  * bytes use. Both come from the read; neither is inferred here, because only
  * the caller has the file.
  */
final case class OriginSnapshot(text: String, hash: ContentHash, lineEnding: LineEnding) derives CanEqual

/** The one step reconciliation asks its caller to perform.
  *
  * The caller performs it, not this module: reading and writing a file needs a
  * platform, and this decision has to be reachable from the command line AND
  * from a page that has no filesystem at all.
  */
enum ReconcileAction derives CanEqual:

  /** Nothing to do, or nothing this mode may do without asking. */
  case DoNothing

  /** Pull: take the origin's text, and make its hash the new base. */
  case AdoptOrigin(text: String, base: ContentHash)

  /** Push: write `text` to the origin, but only if it still hashes to
    * `expecting` — D1's compare-and-swap. The new base is the hash of what the
    * write produced, which the caller learns from the write.
    */
  case WriteOrigin(text: String, expecting: ContentHash)

  /** Converged: both sides hold the same content, so only the agreed baseline
    * is stale. No I/O.
    */
  case AdvanceBase(base: ContentHash)

/** What reconciliation decided.
  *
  * Two cases rather than one with an optional `local`, because an unbound
  * diagram has no three-hash comparison at all — no base to compare against and
  * no origin to compare with. Making that a shape rather than a `None` means a
  * caller cannot read a hash that was never computed.
  */
enum ReconcilePlan derives CanEqual:

  /** No binding. Nothing to reconcile against, and nothing to do. */
  case Unbound

  /** @param local
    *   the hash of the text of the diagram, as the origin would STORE it. The
    *   plan returns it because callers report it. The user can understand a
    *   divergence only if the user can see the two versions that disagree.
    */
  case Bound(state: SyncState, action: ReconcileAction, local: ContentHash)

object ReconcilePlan:
  extension (plan: ReconcilePlan)

    /** The state to report. An unbound diagram is `InSync`: it owes nothing to
      * anything, which is what every caller means when it asks.
      */
    def state: SyncState = plan match
      case Unbound            => SyncState.InSync
      case Bound(state, _, _) => state

    def action: ReconcileAction = plan match
      case Unbound             => ReconcileAction.DoNothing
      case Bound(_, action, _) => action

/** The reconciler decides what to do so that a diagram and its origin agree.
  *
  * The reconciler is PURE, and that is its purpose. Before, `gx sync` held this
  * logic as a private method of the command-line tool, so the app could not use
  * it. An edit in the app changed the text of the diagram and did not change
  * `baseHash`. Then the edit waited for a person to run `gx sync`. The edit was
  * never lost, but it also never got to the file.
  *
  * The caller does the I/O. `gx` reads and writes files directly. The page of
  * the desktop has no file system, so it asks its shell. Both ask the
  * reconciler the same question and get the same answer.
  *
  * See §8 of docs/desktop-open-targets-and-persistence.md, and §5.2 of
  * docs/sources-and-library-architecture.md.
  */
object Reconciler:

  /** @param binding
    *   the binding of the diagram, or None if the diagram has no origin.
    * @param text
    *   the text of the diagram. It is the `local` side of the three-hash
    *   comparison.
    * @param origin
    *   what the caller read at the origin, or None if the origin is gone.
    * @param hashText
    *   the function that hashes text as the origin would STORE it. Each
    *   platform supplies it: `Hashing.ofText` on the JVM, and the digest of the
    *   shell in the page.
    */
  def plan(
      binding:  Option[Binding],
      text:     String,
      origin:   Option[OriginSnapshot],
      hashText: (String, LineEnding) => ContentHash
  ): ReconcilePlan =
    planWith(binding, text, origin, hashText(text, storedWith(origin)))

  /** The convention `local` must be hashed with: the origin's own, or LF if the
    * origin is gone.
    *
    * Public because a caller that cannot hash synchronously has to compute
    * `local` BEFORE it can call [[planWith]], and it must not guess how. The
    * desktop's page is that caller: it has no SHA-256, so it applies this
    * convention to the text and asks its shell for the digest.
    */
  def storedWith(origin: Option[OriginSnapshot]): LineEnding =
    // `base` and `remote` are hashes of FILE BYTES, so `local` must use the
    // same measure. It is the text of the diagram as the code would write it
    // into THIS file, with the convention that the file uses now (V-04).
    //
    // A hash with a fixed LF made each origin with CRLF line ends show `Ahead`
    // permanently. Nobody edited it, but the bytes could not agree. Also, a
    // regeneration with identical bytes got `Diverged` and not `Converged`.
    // SyncState.Converged exists to prevent that false conflict.
    //
    // If the origin is not on the disk, the state is OriginMissing for all
    // values of `local`. A file that the code makes again gets Lf.
    origin.map(_.lineEnding).getOrElse(LineEnding.Lf)

  /** Reconcile with `local` already computed.
    *
    * CAUTION: `local` must be the hash of `text` stored with [[storedWith]]'s
    * answer for this same origin. Hash it any other way and every comparison
    * below is measuring two different things.
    *
    * Separate from [[plan]] because hashing is not always synchronous. The
    * page fetches its digest over IPC, and a `Future` cannot be handed to a
    * function this method would call.
    */
  def planWith(
      binding: Option[Binding],
      text:    String,
      origin:  Option[OriginSnapshot],
      local:   ContentHash
  ): ReconcilePlan =
    binding match
      case None => ReconcilePlan.Unbound
      case Some(binding) =>
        val state = SyncState.of(binding.baseHash, local, origin.map(_.hash))

        val action =
          binding.mode.autoAction(state) match
            case Some(SyncAction.Pull) =>
              // The text the caller already read. Re-reading it would risk a
              // different file from the one this state was computed against.
              origin
                .map(doc => ReconcileAction.AdoptOrigin(doc.text, doc.hash))
                .getOrElse(ReconcileAction.DoNothing)

            case Some(SyncAction.Push) =>
              // `baseHash`, not `local`: the compare-and-swap asks whether the
              // file is still what both sides agreed on. Passing `local` would
              // ask whether the file already holds what we are about to write,
              // which succeeds precisely when the write is unnecessary.
              ReconcileAction.WriteOrigin(text, binding.baseHash)

            case Some(SyncAction.AdvanceBase) => ReconcileAction.AdvanceBase(local)

            case None => ReconcileAction.DoNothing

        ReconcilePlan.Bound(state, action, local)
