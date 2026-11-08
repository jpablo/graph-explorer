package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.gxcore.model.SyncState
import org.jpablo.graphexplorer.viewer.desktop.OriginReconciler
import org.jpablo.graphexplorer.viewer.state.ViewTarget
import org.jpablo.graphexplorer.viewer.widgets.{Button, primary, tiny}

/** This diagram and its origin file disagree, and a person must decide (§8).
  *
  * Only the two states §5.2 calls `needsUser` appear here. `Ahead`, `Behind`
  * and `Converged` settle on their own under a mode that permits the direction,
  * and reporting them would train the reader to ignore this strip.
  *
  * A divergence offers the two resolutions the page can carry out on its own:
  * take the file's version, or keep the library's. §8 lists two more — write
  * the library's version to the file, and detach — and both are still open.
  * The first needs a rule for which binding modes permit the page to write on
  * a person's behalf; the second needs a way for the page to CLEAR a binding,
  * which no page path has, and an answer for a `gx sync` running at that
  * moment.
  *
  * The function takes the TARGET, not the whole `ViewerState`. The diagram
  * alone decides its origin, and nothing in the viewer changes it. With this
  * narrow parameter, a test of the strip needs no viewer.
  */
def OriginStateBanner(target: ViewTarget): Div =
  // Only a diagram has an origin. A loose file IS its file, and an example has
  // no file and no origin.
  val diagramId =
    target match
      case ViewTarget.Diagram(id) => Some(id)
      case _                      => None

  val unresolved =
    diagramId.map(OriginReconciler.unresolvedFor).getOrElse(Signal.fromValue(None))

  div(
    child <-- unresolved.map:
      case Some(OriginReconciler.Unresolved(SyncState.Diverged, Some(_))) =>
        strip(
          "This diagram and its file both changed",
          "Nothing is written and nothing is lost. Choose which version to keep.",
          Button(
            "Take the file's version",
            onClick --> (_ => diagramId.foreach(OriginReconciler.takeOrigin))
          ).tiny,
          Button(
            "Keep this diagram",
            onClick --> (_ => diagramId.foreach(OriginReconciler.keepDiagram))
          ).primary.tiny
        )

      case Some(OriginReconciler.Unresolved(SyncState.Diverged, None)) =>
        // The page has no copy of the file it can offer. It says so, and the
        // next origin event reconciles again with text it can trust.
        strip(
          "This diagram and its file both changed",
          "Nothing is written and nothing is lost. The diagram keeps showing your version."
        )

      case Some(OriginReconciler.Unresolved(SyncState.OriginMissing, _)) =>
        // Neither resolution exists: there is no file version to take, and no
        // hash to agree on.
        strip(
          "This diagram's file is gone",
          "It was deleted or moved. The library's copy is now the only one."
        )

      // Every other state settles on its own, so it has nothing to say.
      case _ => emptyNode
  )

private def strip(title: String, detail: String, actions: Modifier[Div]*) =
  div(
    cls := "origin-state-banner",
    span(cls := "origin-state-title", title),
    span(cls := "origin-state-detail", detail),
    actions
  )
