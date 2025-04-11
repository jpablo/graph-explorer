package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.attributes.views.miniViews.MiniGroupAttributesView
import org.jpablo.graphexplorer.viewer.components.attributes.views.toolbarViews.{ToolbarArrowsAttributesView, ToolbarNodesAttributesView}
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttributeTarget
import org.jpablo.graphexplorer.viewer.models.{ElementIds, IdsByKind}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.*

def SelectionToolbar(projectName: Signal[String], commands: Commands, state: ViewerState) =
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
              "SelectionAttributes",
              state,
              updates = state.elementAttributesUpdates(ElementIds(nodeIds)),
              defaults = Some(state.defaults(AttributeTarget.node))
            )

          case (false, false, true) =>
            MiniGroupAttributesView(
              state = state,
              attrsVar = state.elementAttributesUpdates(ElementIds(clusterIds)),
              defaults = Some(state.defaults(AttributeTarget.graph))
            )

          case (false, false, false) =>
            div("tbd")

          case _ =>
            val elementTypes = Map(
              "edges"    -> (ElementIds(arrowIds), "Arrows"),
              "nodes"    -> (ElementIds(nodeIds), "Nodes"),
              "clusters" -> (ElementIds(clusterIds), "Clusters")
            )

            div(
              cls := "flex flex-row gap-2",
              div(cls := "attributes-title", h2(s"Filter")),
              div(
                cls := "mx-4",
                Select(
                  placeholderText = s"${selectedNodes.size} objects",
                  options = elementTypes.collect {
                    case (key, (ids, description)) if ids.nonEmpty =>
                      s"$description (${ids.size})" -> key
                  }.toList,
                  onChange.mapToValue --> { value =>
                    for (ids, _) <- elementTypes.get(value) do
                      state.selection.set(ids)
                  },
                  cls := "w-full"
                )
              )
            )
  )
