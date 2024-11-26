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
    thead(tr(th("Label"), th("Source"), th(""), th("Target"))),
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
                cls("font-bold") <-- state.isEdgeVisible(arrow.id),
                cls("selected") <-- state.isSelected(arrow.id),
                td(cls := "truncate", cls("selected") <-- state.isSelected(arrow.target), arrow.label),
                td(cls := "truncate", cls("selected") <-- state.isSelected(arrow.source), arrow.source.toString),
                td("→"),
                td(cls := "truncate", cls("selected") <-- state.isSelected(arrow.target), arrow.target.toString),
                onClick.map(_.metaKey) --> state.diagramSelection.handleClickOnArrow(arrow),
                onDblClick
                  .preventDefault
                  .stopPropagation(_.sample(state.isEdgeVisible(arrow.id))) --> { visible =>
                  if visible then
                    state.hideNodes(arrow.nodeIds)
                  else
                    state.showNodes(arrow.nodeIds)
                }
              )
    )
  )
