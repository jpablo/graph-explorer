package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.attributes.views.toolbarViews.{
  ToolbarArrowsAttributesView,
  ToolbarGroupAttributesView,
  ToolbarNodesAttributesView
}
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttributeTarget
import org.jpablo.graphexplorer.viewer.models.{ElementIds, IdsByKind}
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
        state.fullGraph.map(_.summary).distinct
      ).map: (selectedNodes, summary) =>
        val IdsByKind(clusterIds, nodeIds, arrowIds, cellIds) = selectedNodes.classify

        (arrowIds.nonEmpty, nodeIds.nonEmpty, clusterIds.nonEmpty) match
          case (true, false, false) =>
            ToolbarArrowsAttributesView(
              state,
              all.resetSelectionAttributes,
              updates = state.elementAttributesUpdates(ElementIds(arrowIds)),
              defaults = Some(state.defaults(AttributeTarget.edge))
            )

          case (false, true, false) =>
            ToolbarNodesAttributesView(
              state,
              all.resetSelectionAttributes,
              updates = state.elementAttributesUpdates(ElementIds(nodeIds)),
              defaults = Some(state.defaults(AttributeTarget.node))
            )

          case (false, false, true) =>
            ToolbarGroupAttributesView(
              state,
              all.resetSelectionAttributes,
              updates = state.elementAttributesUpdates(ElementIds(clusterIds)),
              defaults = Some(state.defaults(AttributeTarget.graph))
            )

          case (false, false, false) =>
            val selection = Var("nodes")
            div(
              cls := "flex flex-row gap-2",
              Select(
                placeholderText = None,
                options = Seq("Nodes", "Arrows", "Groups").map(label => s"$label defaults" -> label.toLowerCase),
                cls := "w-32 no-outline",
                onChange.mapToValue --> selection
              ),
              // -------------
              // Defaults
              // -------------
              child <-- selection.signal.map:
                case "nodes" =>
                  ToolbarNodesAttributesView(
                    state,
                    all.resetDefaultNodeAttributes,
                    updates = state.defaultAttributesUpdates(AttributeTarget.node)
                  )
                case "arrows" =>
                  ToolbarArrowsAttributesView(
                    state,
                    all.resetDefaultArrowAttributes,
                    updates = state.defaultAttributesUpdates(AttributeTarget.edge)
                  )
                case "groups" =>
                  ToolbarGroupAttributesView(
                    state,
                    all.resetDefaultGroupAttributes,
                    updates = state.defaultAttributesUpdates(AttributeTarget.graph)
                  )
                case _ => div("No selection")
            )

          case _ =>
            val elementTypes = Map(
              "edges"    -> (ElementIds(arrowIds), "Arrows"),
              "nodes"    -> (ElementIds(nodeIds), "Nodes"),
              "clusters" -> (ElementIds(clusterIds), "Clusters")
            )

            div(
              cls := "flex flex-row gap-2",
              Select(
                placeholderText = Some(s"Filter ${selectedNodes.size} objects"),
                options = elementTypes
                  .collect:
                    case (key, (ids, description)) if ids.nonEmpty => s"$description (${ids.size})" -> key
                  .toList,
                onChange.mapToValue --> { value =>
                  for (ids, _) <- elementTypes.get(value) do
                    state.selection.set(ids)
                }
              )
            )
  )
}
