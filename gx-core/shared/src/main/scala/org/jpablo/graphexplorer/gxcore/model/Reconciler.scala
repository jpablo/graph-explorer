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
    *   the record's text hashed as it would be STORED at the origin. Carried
    *   out because callers report it: a divergence is only legible if the user
    *   can see which two versions disagree.
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

/** Deciding what a diagram and its origin owe each other.
  *
  * PURE, and that is the point. `gx sync` held this logic as a private method
  * of the command-line tool, so the app could not reach it: an edit in the app
  * changed the record text, left `baseHash` alone, and waited for someone to
  * run `gx sync`. The edit was never lost, but it never reached the file
  * either.
  *
  * The I/O stays with the caller. `gx` reads and writes files directly; the
  * desktop's page has no filesystem and asks its shell. Both ask this the same
  * question and get the same answer.
  *
  * See §8 of docs/desktop-open-targets-and-persistence.md, and §5.2 of
  * docs/sources-and-library-architecture.md.
  */
object Reconciler:

  /** @param binding
    *   the diagram's binding, or None if it has no origin.
    * @param text
    *   the record's text — the `local` side of the three-hash comparison.
    * @param origin
    *   what the caller read at the origin, or None if it is gone.
    * @param hashText
    *   how to hash text as it would be STORED. A platform supplies this:
    *   `Hashing.ofText` on the JVM, and the shell's own digest in the page.
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
    // `base` and `remote` are hashes of FILE BYTES, so `local` has to be
    // measured the same way: the record's text as it would be written into THIS
    // file, using the convention that file already uses (V-04).
    //
    // Hashing with a fixed LF made every CRLF-authored origin read `Ahead`
    // forever — nothing had been edited, the bytes simply could not agree — and
    // made a byte-identical regeneration land on `Diverged` instead of
    // `Converged`, which is the conflict machine SyncState.Converged exists to
    // prevent.
    //
    // With no origin on disk the state is OriginMissing whatever `local` says,
    // and Lf is what a re-created file would get.
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
