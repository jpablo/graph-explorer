package org.jpablo.graphexplorer.viewer.components.leftPanel

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.jpablo.graphexplorer.viewer.extensions.*
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.scalajs.dom
import org.scalajs.dom.HTMLTableElement

def EdgesList(
    state:               ViewerState,
    onlyActiveEdges:     Var[Boolean],
    filterEdgesByNodeId: Signal[String]
): ReactiveHtmlElement[HTMLTableElement] =
  table(
    cls := "table table-xs table-pin-rows",
    thead(tr(th("Source"), th(""), th("Target"), th("Label"))),
    tbody(
      children <--
        state
          .fullGraph
          .combineWith(onlyActiveEdges, filterEdgesByNodeId, state.hiddenNodesS)
          .map: (fullGraph, onlyActive, str, hiddenNodes) =>
            fullGraph
              .orElse(!onlyActive, _.removeNodes(hiddenNodes))
              .filterArrowsBy(a => a.source.toString.contains(str) || a.target.toString.contains(str))
              .toList
              .sorted
          .map:
            _.map: arrow =>
              tr(
                cls := "whitespace-nowrap hover cursor-pointer",
                cls("font-bold") <-- state.isEdgeVisible(arrow.nodeId),
                cls("selected") <-- state.isSelected(arrow.nodeId),
                td(cls := "truncate", cls("selected") <-- state.isSelected(arrow.source), arrow.source.toString),
                td("→"),
                td(cls := "truncate", cls("selected") <-- state.isSelected(arrow.target), arrow.target.toString),
                td(cls := "truncate", cls("selected") <-- state.isSelected(arrow.target), arrow.label),
                onClick.map(_.metaKey) --> state.diagramSelection.handleClickOnArrow(arrow),
                onDblClick
                  .preventDefault
                  .stopPropagation(_.sample(state.isEdgeVisible(arrow.nodeId))) --> { visible =>
                  if visible then
                    state.hideNodes(arrow.nodeIds)
                  else
                    state.showNodes(arrow.nodeIds)
                }
              )
    )
  )
