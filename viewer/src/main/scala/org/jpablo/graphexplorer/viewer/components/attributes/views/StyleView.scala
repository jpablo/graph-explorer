package org.jpablo.graphexplorer.viewer.components.attributes.views

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.attributes.views.{GraphAttributesView, NodesAttributesView}
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttributeTarget
import org.jpablo.graphexplorer.viewer.models.{ElementIds, IdsByKind}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.Select

def StyleView(state: ViewerState) =
  div(
    idAttr := "style-view",
    child <--
      Signal.combine(
        state.selection.signal,
        state.fullGraph.map(_.summary)
      )
        .map: (selectedNodes, summary) =>
          val IdsByKind(clusterIds, nodeIds, arrowIds) = selectedNodes.classify

          (arrowIds.nonEmpty, nodeIds.nonEmpty, clusterIds.nonEmpty) match
            case (true, false, false) =>
              div(
                div(
                  cls := "attributes-title",
                  h2(s"Selected Arrows"),
                  span(s" ${arrowIds.size} / ${summary.arrows}")
                ),
                EdgesAttributesView(
                  state,
                  updates   = state.elementAttributes(ElementIds(arrowIds)),
                  defaults  = Some(state.defaults(AttributeTarget.edge)),
                  selection = true
                ).amend(cls("selection-attributes"))
              )

            case (false, true, false) =>
              div(
                div(
                  cls := "attributes-title",
                  h2(s"Selected Nodes"),
                  span(s" ${nodeIds.size} / ${summary.nodes}")
                ),
                NodesAttributesView(
                  "SelectionAttributes",
                  state,
                  updates   = state.elementAttributes(ElementIds(nodeIds)),
                  defaults  = Some(state.defaults(AttributeTarget.node)),
                  selection = true
                ).amend(cls("selection-attributes"))
              )

            case (false, false, true) =>
              div(
                div(
                  cls := "attributes-title",
                  h2(s"Selected Groups"),
                  span(s" ${clusterIds.size} / ${summary.groups}")
                ),
                GraphAttributesView(
                  state     = state,
                  attrsVar  = state.elementAttributes(ElementIds(clusterIds)),
                  defaults  = Some(state.defaults(AttributeTarget.graph)),
                  selection = true
                ).amend(cls("selection-attributes"))
              )

            case (false, false, false) =>
              div(
                div(cls := "attributes-title", h2("Diagram")),
                RootGraphAttributesView(state)
              )

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
