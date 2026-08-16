package org.jpablo.graphexplorer.gxcore.model

/** Which directions a binding is allowed to move content.
  *
  * A mode is not a free choice: it is bounded by what the origin's scheme can
  * actually do (`https:` cannot be written), so [[OriginScheme]] validates it at
  * bind time. See docs/sources-and-library-architecture.md §5.1.
  */
enum SyncMode derives CanEqual:
  /** Imported once; the origin is kept for provenance and nothing syncs. */
  case Detached

  /** origin -> diagram. The generator flow: an LLM or build step writes the
    * file, the diagram follows. UI edits are kept locally and never written
    * back, and a local edit blocks the auto-pull rather than being silently
    * overwritten (§5.3).
    */
  case Pull

  /** diagram -> origin. The app is the author and the file is output. */
  case Push

  /** Both. Needs the conflict handling in [[SyncState.Diverged]]. */
  case Sync

object SyncMode:
  extension (m: SyncMode)
    def pulls: Boolean = m match
      case Pull | Sync           => true
      case Detached | Push       => false

    def pushes: Boolean = m match
      case Push | Sync           => true
      case Detached | Pull       => false

    /** What to do about a [[SyncState]] without asking the user.
      *
      * Returns None when the answer is "nothing automatic" — either because
      * there is nothing to do, or because the mode forbids that direction, or
      * because it genuinely needs a human.
      */
    def autoAction(state: SyncState): Option[SyncAction] = state match
      case SyncState.InSync        => None
      case SyncState.Converged     => Some(SyncAction.AdvanceBase)
      case SyncState.Ahead         => Option.when(m.pushes)(SyncAction.Push)
      case SyncState.Behind        => Option.when(m.pulls)(SyncAction.Pull)
      case SyncState.Diverged      => None
      case SyncState.OriginMissing => None

/** The reconciliation step a mode permits for a given state. */
enum SyncAction derives CanEqual:
  case Pull, Push

  /** No I/O: both sides already hold the same bytes, so only the agreed-on
    * baseline is stale. This is what keeps a byte-identical regeneration from
    * looking like a change.
    */
  case AdvanceBase
