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
  val filteredGraph = filteredDiagramEvent(state, onlyActiveVar.signal, filterVar.signal)

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
          title := "Select filtered nodes",
          "Select",
          onClick.preventDefault(_.sample(filteredGraph)) --> { graph =>
            state.diagramSelection.set(graph.nodesSet.map(_.id))
          }
        )
      )
    ),
    div(
      idAttr := "nodes-panel-contents",
      table(
        cls := "table table-xs table-pin-rows",
        thead(tr(th("NodeId"), th("Label"))),
        tbody(
          children <--
            filteredGraph
              .map(_.nodesSet.toList.sortBy(_.id.value))
              .map:
                _.map: node =>
                  tr(
                    cls := "whitespace-nowrap hover cursor-pointer",
                    cls("font-bold") <-- state.isNodeVisible(node.id),
                    cls("bg-base-200") <-- state.isSelected(node.id),
                    td(cls := "truncate", node.id.toString),
                    td(cls := "truncate", node.label.toString),
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
