package org.jpablo.graphexplorer.viewer.components.rightPanel

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.jpablo.graphexplorer.viewer.extensions.*
import org.jpablo.graphexplorer.viewer.models.Arrow
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.scalajs.dom
import org.jpablo.graphexplorer.viewer.widgets.{Join, LabeledCheckbox, Search}
import org.jpablo.graphexplorer.viewer.widgets.smallInput
import com.raquo.airstream.state.Var

def EdgesList(
    state: ViewerState,
    onlyActiveEdges: Var[Boolean],
): ReactiveHtmlElement[dom.HTMLDivElement] =
  val filterEdgesByNodeId = Var("")

  def arrowEndpoints(arrow: Arrow): (String, String) =
    val Seq(sourceNode, targetNode) = state.getNodeById(Seq(arrow.source, arrow.target))
    val sl = sourceNode.label.toString
    val tl = targetNode.label.toString
    (if sl.isBlank then arrow.source.toString else sl, if tl.isBlank then arrow.target.toString else tl)

  div(
    form(
      idAttr := "edges-panel-controls",
      Join(LabeledCheckbox(id = s"filter-by-active", labelStr = "only visible", isChecked = onlyActiveEdges)),
      div(
        cls := "flex gap-2",
        Search(
          placeholder := "filter",
          controlled(value <-- filterEdgesByNodeId, onInput.mapToValue --> filterEdgesByNodeId)
        ).smallInput,
        button(
          cls := "btn btn-xs",
          title := "Select filtered edges",
          "Select",
          onClick.preventDefault(_.sample(state.fullGraph.combineWith(onlyActiveEdges, filterEdgesByNodeId.signal, state.hiddenNodesS))) --> { case (fullGraph, onlyActive, str, hiddenNodes) =>
            val filteredEdges = fullGraph
              .orElse(!onlyActive, _.removeNodes(hiddenNodes))
              .filterArrowsBy(a => a.source.toString.contains(str) || a.target.toString.contains(str))
              .toList
              .map(_.id)
              .toSet
            state.diagramSelection.set(filteredEdges)
          }
        )
      )
    ),
    div(
      idAttr := "edges-panel-contents",
      table(
        cls := "table table-xs table-pin-rows",
        thead(tr(th("Label"), th("Source"), th(""), th("Target"))),
        tbody(
          children <--
            state
              .fullGraph
              .combineWith(onlyActiveEdges, filterEdgesByNodeId.signal, state.hiddenNodesS)
              .map: (fullGraph, onlyActive, str, hiddenNodes) =>
                fullGraph
                  .orElse(!onlyActive, _.removeNodes(hiddenNodes))
                  .filterArrowsBy(a => a.source.toString.contains(str) || a.target.toString.contains(str))
                  .toList
                  .sorted
              .map:
                _.map: arrow =>
                  val (sourceLabel, targetLabel) = arrowEndpoints(arrow)
                  tr(
                    cls := "whitespace-nowrap hover cursor-pointer",
                    cls("font-bold") <-- state.isEdgeVisible(arrow.id),
                    cls("selected") <-- state.isSelected(arrow.id),
                    td(cls := "truncate", arrow.label.toString),
                    td(cls := "truncate", cls("selected") <-- state.isSelected(arrow.source), sourceLabel),
                    td("→"),
                    td(cls := "truncate", cls("selected") <-- state.isSelected(arrow.target), targetLabel),
                    onMouseDown.preventDefault --> Observer.empty,
                    onClick.map(_.shiftKey) --> state.diagramSelection.handleClickOnArrow(arrow),
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
    )
  )
