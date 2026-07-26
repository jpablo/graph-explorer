package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.attributes.views.toolbarViews.{
  ToolbarArrowsAttributesView,
  ToolbarGroupAttributesView,
  ToolbarNodesAttributesView
}
import org.jpablo.graphexplorer.viewer.models.{ElementIds, IdsByKind}
import org.jpablo.graphexplorer.viewer.selection.{ElementKind, SelectByKind}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.*

def AttributesToolbar(projectName: Signal[String], commands: Commands, state: ViewerState) = {
  import commands.all
  div(
    idAttr := "selection-toolbar",
    cls    := "navbar",
    // Keyed on the SELECTION alone. Keying it on the graph too rebuilt this whole subtree on
    // every attribute write — and the write comes from a control inside the subtree, so the
    // bar destroyed the control being used. A slider could not be dragged at all: the first
    // mousedown wrote a value, the value rebuilt the bar, and the drag continued against an
    // element no longer in the document. Dropdowns close for the same reason, daisyUI's being
    // `:focus-within` and a fresh subtree holding no focus.
    //
    // Nothing here needs the rebuild to stay current: the rows read `elementAttributesUpdates`,
    // a zoomLens Var over the live graph, so they track edits on their own. The one branch that
    // genuinely reads the graph — "select by kind", shown when nothing is selected and so never
    // mid-gesture — takes it from an inner `child <--`.
    child <--
      state.selection.signal.distinct.map: selectedNodes =>
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
            div(
              cls := "flex flex-row gap-2",
              child <-- state.visibleGraph.distinct.map: visibleGraph =>
                val options = SelectByKind.optionsForGraph(visibleGraph)
                Select(
                  placeholderText = Some("Select by kind"),
                  options = options.map(option => option.label -> option.kind.id),
                  onChange.mapToValue --> { value =>
                    ElementKind.fromId(value).foreach: kind =>
                      state.selection.set(SelectByKind.idsForGraph(visibleGraph, kind))
                  }
                )
            )

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
  )
}
