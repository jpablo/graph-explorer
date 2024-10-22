package org.jpablo.graphexplorer.viewer.components.leftPanel

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.jpablo.graphexplorer.viewer.extensions.*
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.scalajs.dom
import org.scalajs.dom.HTMLTableElement

def NodesList(
    state:          ViewerState,
    onlyActive:     Signal[Boolean],
    filterByNodeId: Signal[String]
): ReactiveHtmlElement[HTMLTableElement] =
  table(
    cls := "table table-xs table-pin-rows",
    thead(tr(th("Node"), th("Label"))),
    tbody(
      children <--
        filteredDiagramEvent(state, onlyActive, filterByNodeId)
          .map(_.nodes.toList.sortBy(_.displayName))
          .map:
            _.map: node =>
              tr(
                cls := "whitespace-nowrap hover cursor-pointer",
                cls("font-bold") <-- state.isNodeVisible(node.id),
                cls("bg-base-200") <-- state.isSelected(node.id),
                td(cls := "truncate", cls("italic") <-- state.isSelected(node.id), node.id.toString),
                td(cls := "truncate", cls("italic") <-- state.isSelected(node.id), node.displayName),
                onClick.map(_.metaKey) --> state.diagramSelection.handleClickOnNode(node.id),
                onDblClick
                  .preventDefault
                  .stopPropagation(_.sample(state.isNodeVisible(node.id))) --> { visible =>
                  if visible then
                    state.hideNodes(Set(node.id))
                  else
                    state.showNodes(Set(node.id))
                }
              )
    )
  )

private def filteredDiagramEvent(
    state:          ViewerState,
    onlyActive:     Signal[Boolean],
    filterByNodeId: Signal[String]
): Signal[ViewerGraph] = state
  .fullGraph
  .combineWith(onlyActive, filterByNodeId, state.hiddenNodesS)
  .map: (fullGraph, onlyActive, filter, hiddenNodes) =>
    fullGraph.orElse(filter.isBlank, _.filterByNodeId(filter)).orElse(!onlyActive, _.removeNodes(hiddenNodes))
