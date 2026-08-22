package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.gxcore.model.SyncState
import org.jpablo.graphexplorer.viewer.desktop.OriginReconciler
import org.jpablo.graphexplorer.viewer.state.{ViewerState, ViewTarget}

/** This record and its origin file disagree, and a person must decide (§8).
  *
  * Only the two states §5.2 calls `needsUser` appear here. `Ahead`, `Behind`
  * and `Converged` settle on their own under a mode that permits the direction,
  * and reporting them would train the reader to ignore this strip.
  *
  * The strip states the situation and does not offer the resolution actions
  * yet. §8 lists four — take the file, keep the library version, write the
  * library version to the file, detach — and each needs a decision this phase
  * has not made: whether the page may push, and what "detach" does to a record
  * `gx` may be syncing at the same moment.
  */
def OriginStateBanner(state: ViewerState): Div =
  val syncState =
    state.target match
      case ViewTarget.LibraryDiagram(id) => OriginReconciler.state(id)
      // Only a record has an origin. A loose file IS its file, and an example
      // has neither.
      case _ => Signal.fromValue(None)

  div(
    child <-- syncState.map:
      case Some(SyncState.Diverged) =>
        strip(
          "This diagram and its file both changed",
          "Nothing is written and nothing is lost. The diagram keeps showing your version."
        )
      case Some(SyncState.OriginMissing) =>
        strip(
          "This diagram's file is gone",
          "It was deleted or moved. The library's copy is now the only one."
        )
      // Every other state settles on its own, so it has nothing to say.
      case _ => emptyNode
  )

private def strip(title: String, detail: String) =
  div(
    cls := "origin-state-banner",
    span(cls := "origin-state-title", title),
    span(cls := "origin-state-detail", detail)
  )
