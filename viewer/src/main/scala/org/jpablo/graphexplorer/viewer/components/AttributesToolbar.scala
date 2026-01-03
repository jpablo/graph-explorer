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
    child <--
      Signal.combine(
        state.selection.signal,
        state.visibleGraph.distinct
      ).map: (selectedNodes, visibleGraph) =>
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
            val options = SelectByKind.optionsForGraph(visibleGraph)
            div(
              cls := "flex flex-row gap-2",
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
