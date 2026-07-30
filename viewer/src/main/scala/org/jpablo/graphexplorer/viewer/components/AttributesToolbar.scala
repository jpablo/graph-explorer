package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.components.attributes.views.toolbarViews.{
  ToolbarArrowsAttributesView,
  ToolbarGroupAttributesView,
  ToolbarNodesAttributesView
}
import org.jpablo.graphexplorer.viewer.models.{ElementIds, IdsByKind}
import org.jpablo.graphexplorer.viewer.selection.{ElementKind, SelectByKind}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.*

/** The CONTEXT STRIP: a second row that exists only while it has something to
  * say — attribute controls for the current selection, and a "N hidden" chip
  * while elements are hidden. When neither applies, the row is gone and the
  * canvas starts right under the toolbar (see the top-bar design study: an
  * always-on second bar reads as two half-empty bars).
  */
def AttributesToolbar(projectName: Signal[String], commands: Commands, state: ViewerState) =
  import commands.all

  val hiddenCount  = state.hiddenElements.signal.map(_.size)
  val hasSelection = state.selection.signal.map(_.nonEmpty)
  val stripVisible = hasSelection.combineWithFn(hiddenCount)(_ || _ > 0)

  div(
    idAttr := "selection-toolbar",
    cls    := "navbar",
    cls("hidden") <-- stripVisible.not,
    // What the controls apply to, named: "2 nodes", "3 objects".
    child.maybe <-- state.selection.signal.distinct.map: sel =>
      Option.when(sel.nonEmpty)(span(cls := "gx-selection-count", selectionSummary(sel))),
    // Keyed on the SELECTION alone. Keying it on the graph too rebuilt this whole subtree on
    // every attribute write — and the write comes from a control inside the subtree, so the
    // bar destroyed the control being used. A slider could not be dragged at all: the first
    // mousedown wrote a value, the value rebuilt the bar, and the drag continued against an
    // element no longer in the document. Dropdowns close for the same reason, daisyUI's being
    // `:focus-within` and a fresh subtree holding no focus.
    //
    // Nothing here needs the rebuild to stay current: the rows read `elementAttributesUpdates`,
    // a zoomLens Var over the live graph, so they track edits on their own.
    //
    // A flex row, not a plain block: the selection views were designed as
    // shrink-to-fit flex items of the navbar, and daisyUI's select resolves its
    // `100%` width cap against a definite block width — a stretched wrapper
    // inflated the filter select to the full 20rem cap.
    div(
      cls := "flex-1 min-w-0 flex items-center",
      child <-- state.selection.signal.distinct.map(selectionView(state, commands, _))
    ),
    // Hidden elements are invisible by definition — this chip is their one
    // visible trace, and the way back. Lives here (not in the toolbar) because
    // it is context about THIS view, present exactly while it applies.
    div(
      cls := "shrink-0",
      cls("hidden") <-- hiddenCount.map(_ == 0),
      Button(
        text <-- hiddenCount.map(n => s"$n hidden · Show all"),
        onClick --> all.showAll.execute()
      ).tiny.soft.primary.toTooltip(all.showAll.labelWithShortcut)
    )
  )

private def selectionSummary(sel: ElementIds): String =
  val IdsByKind(clusterIds, nodeIds, arrowIds) = sel.classify
  def part(n: Int, noun: String) = Option.when(n > 0)(s"$n $noun${if n == 1 then "" else "s"}")
  (part(nodeIds.size, "node"), part(arrowIds.size, "arrow"), part(clusterIds.size, "group")) match
    case (Some(s), None, None) => s
    case (None, Some(s), None) => s
    case (None, None, Some(s)) => s
    case _                     => s"${sel.size} objects"

private def selectionView(state: ViewerState, commands: Commands, selectedNodes: ElementIds) =
  import commands.all
  val IdsByKind(clusterIds, nodeIds, arrowIds) = selectedNodes.classify

  (arrowIds.nonEmpty, nodeIds.nonEmpty, clusterIds.nonEmpty) match
    case (true, false, false) =>
      ToolbarArrowsAttributesView(
        state,
        all.resetSelectionAttributes,
        updates = state.elementAttributesUpdates(ElementIds(arrowIds))
      )

    case (false, true, false) =>
      ToolbarNodesAttributesView(
        state,
        all.resetSelectionAttributes,
        updates = state.elementAttributesUpdates(ElementIds(nodeIds))
      )

    case (false, false, true) =>
      ToolbarGroupAttributesView(
        state,
        all.resetSelectionAttributes,
        updates = state.elementAttributesUpdates(ElementIds(clusterIds))
      )

    case (false, false, false) =>
      // Nothing selected: the strip is only visible for the hidden-elements
      // chip. Selecting by kind lives in the Select menu and ⌘K now.
      div()

    case _ =>
      val options = SelectByKind.optionsForSelection(selectedNodes.classify)
      div(
        cls := "flex flex-row gap-2",
        Select(
          placeholderText = Some(s"Filter ${selectedNodes.size} objects"),
          options = options.map(option => option.label -> option.kind.id),
          onChange.mapToValue --> { value =>
            ElementKind.fromId(value).foreach: kind =>
              state.selection.set(SelectByKind.idsForSelection(selectedNodes.classify, kind))
          }
        )
      )
