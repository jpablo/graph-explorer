package org.jpablo.graphexplorer.viewer.components.attributes.views

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.attributes.views.{GraphAttributesView, NodesAttributesView}
import org.jpablo.graphexplorer.viewer.models.{ElementIds, IdsByKind}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.Select

def StyleView(state: ViewerState) =
  div(
    idAttr := "diagram-attributes",
    child <--
      state.diagramSelection.signal.map: selectedNodes =>
        val IdsByKind(clusterIds, nodeIds, arrowIds) = selectedNodes.classify

        (arrowIds.nonEmpty, nodeIds.nonEmpty, clusterIds.nonEmpty) match
          case (true, false, false) =>
            div(
              div(
                cls := "divider",
                div(cls := "divider-content", h2(cls := "text-lg font-semibold", s"Selected Arrows (${arrowIds.size})"))
              ),
              EdgesAttributesView(
                state,
                updates     = state.elementAttributes(ElementIds(arrowIds)),
                defaults  = Some(state.visibleGraph.map(_.root.edgeAttrs)),
                selection = true
              ).amend(cls("selection-attributes"))
            )

          case (false, true, false) =>
            div(
              div(
                cls := "divider",
                div(cls := "divider-content", h2(cls := "text-lg font-semibold", s"Selected Nodes (${nodeIds.size})"))
              ),
              NodesAttributesView(
                "SelectionAttributes",
                state,
                updates  = state.elementAttributes(ElementIds(nodeIds)),
                defaults  = Some(state.visibleGraph.map(_.root.nodeAttrs)),
                selection = true
              ).amend(cls("selection-attributes"))
            )

          case (false, false, true) =>
            div(
              div(
                cls := "divider",
                div(
                  cls := "divider-content",
                  h2(cls := "text-lg font-semibold", s"Selected Clusters (${clusterIds.size})")
                )
              ),
              GraphAttributesView(
                state     = state,
                attrsVar  = state.elementAttributes(ElementIds(clusterIds)),
                defaults  = Some(state.visibleGraph.map(_.root.attributes)),
                selection = true
              ).amend(cls("selection-attributes"))
            )

          case (false, false, false) =>
            GeneralAttributesView(state)

          case _ =>
            val elementTypes = Map(
              "edges"    -> (ElementIds(arrowIds), "Arrows"),
              "nodes"    -> (ElementIds(nodeIds), "Nodes"),
              "clusters" -> (ElementIds(clusterIds), "Clusters")
            )

            div(
              div(cls := "divider", div(cls := "divider-content", h2(cls := "text-lg font-semibold", s"Filter"))),
              Select(
                placeholderText = s"${selectedNodes.size} objects",
                options = elementTypes.collect {
                  case (key, (ids, description)) if ids.nonEmpty =>
                    s"$description (${ids.size})" -> key
                }.toList,
                onChange.mapToValue --> { value =>
                  for (ids, _) <- elementTypes.get(value) do
                    state.diagramSelection.set(ids)
                },
                cls := "w-full mb-4"
              )
            )
  )
