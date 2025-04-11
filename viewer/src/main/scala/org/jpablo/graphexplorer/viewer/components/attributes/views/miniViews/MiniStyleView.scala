package org.jpablo.graphexplorer.viewer.components.attributes.views.miniViews

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttributeTarget
import org.jpablo.graphexplorer.viewer.models.{ElementIds, IdsByKind}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.Select

def MiniStyleView(state: ViewerState) =
  div(
    idAttr := "mini-style-view",
    child <--
      Signal.combine(
        state.selection.signal,
        state.fullGraph.map(_.summary).distinct
      )
        .map: (selectedNodes, summary) =>
          val IdsByKind(clusterIds, nodeIds, arrowIds) = selectedNodes.classify

          (arrowIds.nonEmpty, nodeIds.nonEmpty, clusterIds.nonEmpty) match
            case (true, false, false) =>
              div(
                MiniArrowsAttributesView(
                  state,
                  updates = state.elementAttributesUpdates(ElementIds(arrowIds)),
                  defaults = Some(state.defaults(AttributeTarget.edge)),
                ).amend(cls("selection-attributes"))
              )

            case (false, true, false) =>
              div(
                MiniNodesAttributesView(
                  "SelectionAttributes",
                  state,
                  updates = state.elementAttributesUpdates(ElementIds(nodeIds)),
                  defaults = Some(state.defaults(AttributeTarget.node))
                ).amend(cls("selection-attributes"))
              )

            case (false, false, true) =>
              div(
                MiniGroupAttributesView(
                  state = state,
                  attrsVar = state.elementAttributesUpdates(ElementIds(clusterIds)),
                  defaults = Some(state.defaults(AttributeTarget.graph)),
                ).amend(cls("selection-attributes"))
              )

            case (false, false, false) =>
              div()

            case _ =>
              val elementTypes = Map(
                "edges"    -> (ElementIds(arrowIds), "Arrows"),
                "nodes"    -> (ElementIds(nodeIds), "Nodes"),
                "clusters" -> (ElementIds(clusterIds), "Clusters")
              )

              div(
                div(cls := "attributes-title", h2(s"Filter")),
                div(
                  cls := "mx-4",
                  Select(
                    placeholderText = Some(s"${selectedNodes.size} objects"),
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
