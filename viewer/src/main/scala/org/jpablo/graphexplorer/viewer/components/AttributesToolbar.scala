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

def AttributesToolbar(projectName: Signal[String], commands: Commands, state: ViewerState) =
  div(
    idAttr := "selection-toolbar",
    cls    := "navbar bg-base-200/75",
    child <--
      Signal.combine(
        state.selection.signal,
        state.fullGraph.map(_.summary).distinct
      ).map: (selectedNodes, summary) =>
        val IdsByKind(clusterIds, nodeIds, arrowIds) = selectedNodes.classify

        (arrowIds.nonEmpty, nodeIds.nonEmpty, clusterIds.nonEmpty) match
          case (true, false, false) =>
            ToolbarArrowsAttributesView(
              state,
              updates = state.elementAttributesUpdates(ElementIds(arrowIds)),
              defaults = Some(state.defaults(AttributeTarget.edge))
            )

          case (false, true, false) =>
            ToolbarNodesAttributesView(
              state,
              updates = state.elementAttributesUpdates(ElementIds(nodeIds)),
              defaults = Some(state.defaults(AttributeTarget.node))
            )

          case (false, false, true) =>
            ToolbarGroupAttributesView(
              state = state,
              updates = state.elementAttributesUpdates(ElementIds(clusterIds)),
              defaults = Some(state.defaults(AttributeTarget.graph))
            )

          case (false, false, false) =>
            val selection = Var("nodes")
            div(
              cls := "flex flex-row gap-2",
              Tooltip(
                text = "Defaults",
                cls := "tooltip-top",
                Select(
                  placeholderText = None,
                  options = Seq("Nodes", "Arrows", "Groups").map(label => label -> label.toLowerCase),
                  cls := "w-22 no-outline",
                  onChange.mapToValue --> selection
                )
              ),
              child <-- selection.signal.map:
                case "nodes"  => ToolbarNodesAttributesView(state, updates = state.defaultAttributesUpdates(AttributeTarget.node))
                case "arrows" => ToolbarArrowsAttributesView(state, updates = state.defaultAttributesUpdates(AttributeTarget.edge))
                case "groups" => ToolbarGroupAttributesView(state, updates = state.defaultAttributesUpdates(AttributeTarget.graph))
                case _        => div("No selection")
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
