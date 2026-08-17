package org.jpablo.graphexplorer.gxcore.model

/** How a diagram and its origin relate right now.
  *
  * Computed from three hashes, never stored: `base` (what both sides last agreed
  * on, held in the [[Binding]]), `local` (the store's text) and `remote` (the
  * origin's content). This is git's fetch/status model, and copying it is the
  * point — it is well understood, it needs no coordination, and it makes
  * divergence a representable state rather than an error.
  *
  * See docs/sources-and-library-architecture.md §5.2.
  */
enum SyncState derives CanEqual:
  /** Nobody has moved. */
  case InSync

  /** Local edits the origin has not seen. Pushed automatically in Push/Sync;
    * in Pull this is a stable resting state — the edits stay local until the
    * binding is changed (§5.3).
    */
  case Ahead

  /** The origin moved and we have no local edits. The generator flow: pull it. */
  case Behind

  /** Both moved, differently. NOT an error: nothing is written, nothing is lost,
    * and the diagram keeps rendering local text. The user resolves with
    * keep-mine / take-theirs. With an LLM writing the file this is a normal
    * state, which is why it has to be representable rather than exceptional.
    */
  case Diverged

  /** Both moved and arrived at the same content. Advance `base`; do no I/O.
    *
    * The row that is easy to omit and expensive to omit: a generator that
    * rewrites a file to byte-identical content hits this on every run. Treating
    * it as `Diverged` would make the primary use case a conflict machine.
    */
  case Converged

  /** The origin is gone — deleted or moved out from under the binding.
    *
    * Distinguished from every other state because the response differs: there
    * is nothing to reconcile against, and the local text is now the only copy.
    * v1 produced silence here; V-06 requires something actionable.
    */
  case OriginMissing

object SyncState:
  /** @param base   what both sides last agreed on
    * @param local  the store's current text
    * @param remote the origin's current content, or None if the origin is gone
    */
  def of(base: ContentHash, local: ContentHash, remote: Option[ContentHash]): SyncState =
    remote match
      case None => OriginMissing
      case Some(remote) =>
        val localMoved  = local != base
        val remoteMoved = remote != base
        (localMoved, remoteMoved) match
          case (false, false) => InSync
          case (true, false)  => Ahead
          case (false, true)  => Behind
          case (true, true)   => if local == remote then Converged else Diverged

  extension (s: SyncState)
    /** Whether reconciliation needs a human. Everything else the sync loop can
      * settle on its own, given a [[SyncMode]] that permits the direction.
      */
    def needsUser: Boolean = s match
      case Diverged | OriginMissing            => true
      case InSync | Ahead | Behind | Converged => false
