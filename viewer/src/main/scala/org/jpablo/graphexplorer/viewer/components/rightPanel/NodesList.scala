package org.jpablo.graphexplorer.viewer.components.rightPanel

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.jpablo.graphexplorer.viewer.extensions.*
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.scalajs.dom
import org.jpablo.graphexplorer.viewer.widgets.{Join, LabeledCheckbox, Search}
import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.widgets.smallInput

def NodesList(
    state:          ViewerState,
    onlyActiveVar:  Var[Boolean],
    filterVar:      Var[String]
): ReactiveHtmlElement[dom.HTMLDivElement] =
  div(
    form(
      idAttr := "nodes-panel-controls",
      Join(LabeledCheckbox(id = s"filter-by-active", labelStr = "only visible", isChecked = onlyActiveVar)),
      div(
        cls := "flex gap-2",
        Search(
          placeholder := "filter",
          controlled(value <-- filterVar, onInput.mapToValue --> filterVar)
        ).smallInput,
        button(
          cls := "btn btn-xs",
          "Select All",
          onClick.preventDefault --> { _ =>
            given Owner = state.owner
            val filteredGraph = filteredDiagramEvent(state, onlyActiveVar.signal, filterVar.signal).observe().now()
            state.diagramSelection.set(filteredGraph.nodesSet.map(_.id))
          }
        )
      )
    ),
    div(
      idAttr := "nodes-panel-contents",
      table(
        cls := "table table-xs table-pin-rows",
        thead(tr(th("Label"), th("NodeId"))),
        tbody(
          children <--
            filteredDiagramEvent(state, onlyActiveVar.signal, filterVar.signal)
              .map(_.nodesSet.toList.sortBy(_.id.value))
              .map:
                _.map: node =>
                  tr(
                    cls := "whitespace-nowrap hover cursor-pointer",
                    cls("font-bold") <-- state.isNodeVisible(node.id),
                    cls("selected") <-- state.isSelected(node.id),
                    td(cls := "truncate", cls("italic") <-- state.isSelected(node.id), node.label.toString),
                    td(cls := "truncate", cls("italic") <-- state.isSelected(node.id), node.id.toString),
                    onMouseDown.preventDefault --> Observer.empty,
                    onClick.preventDefault.map(_.shiftKey) --> state.diagramSelection.handleClickOnNode(node.id),
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
    fullGraph
      .orElse(filter.isBlank, _.filterByNodeId(filter))
      .orElse(!onlyActive, _.removeNodes(hiddenNodes))
