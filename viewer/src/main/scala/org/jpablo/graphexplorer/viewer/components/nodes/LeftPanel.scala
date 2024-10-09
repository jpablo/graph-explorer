package org.jpablo.graphexplorer.viewer.components.nodes

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import io.laminext.syntax.core.*
import org.jpablo.graphexplorer.viewer.backends.graphviz.DotExamples
import org.jpablo.graphexplorer.viewer.backends.graphviz.DotExamples.examples
import org.jpablo.graphexplorer.viewer.extensions.*
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.*

def LeftPanel(state: ViewerState) =
  val visibleTab = state.leftPanelTabIndex
  val filterNodesByNodeId = Var("")
  val filterEdgesByNodeId = Var("")
  def isVisible(i: Int) = visibleTab.signal.map(_ == i)
  val onlyActiveNodes = Var(false)
  val onlyActiveEdges = Var(false)
  div(
    idAttr := "nodes-panel",
    div(
      idAttr := "nodes-panel-tab-buttons",
      Button("Source", cls("btn-active") <-- isVisible(0), onClick --> visibleTab.set(0)).tiny,
      Button(
        child <-- state.fullGraph.map(_.summary.nodes).map(n => s"Nodes ($n)"),
        cls("btn-active") <-- isVisible(1),
        onClick --> visibleTab.set(1)
      ).tiny,
      Button(
        child <-- state.fullGraph.map(_.summary.arrows).map(n => s"Edges ($n)"),
        cls("btn-active") <-- isVisible(2),
        onClick --> visibleTab.set(2)
      ).tiny
    ),
    // ------ TAB 0: Source ------
    // --- DOT sources ---
    div(
      cls := "flex gap-2",
      cls("hidden") <-- !isVisible(0),
      select(
        cls := "select select-bordered select-xs max-w-xs",
        option("Select example", disabled := true, selected := true),
        examples.keys.map(name => option(name, value := name)),
        onChange.mapToValue.map(examples).flatMap(FetchStream.get(_)) --> state.source.set
      ),
      a(
        cls    := "link",
        href   := "https://www.graphviz.org/documentation/",
        target := "_blank",
        title  := "Visit the Graphviz documentation for more information",
        "Graphviz"
      )
    ),
    textArea(
      idAttr := "nodes-source",
      cls    := "textarea textarea-bordered",
      cls("hidden") <-- !isVisible(0),
      placeholder := "DOT source",
      value <-- state.source,
      onInput.mapToValue.compose(_.debounce(300)) --> state.source.set
    ),

    // ------ TAB 1: Nodes ------
    // --- controls ---
    form(
      idAttr := "nodes-panel-controls",
      cls("hidden") <-- !isVisible(1),
      Join(LabeledCheckbox(id = s"filter-by-active", labelStr = "only active", isChecked = onlyActiveNodes)),
      Search(
        placeholder := "filter",
        controlled(value <-- filterNodesByNodeId, onInput.mapToValue --> filterNodesByNodeId)
      ).smallInput
    ),
    // Scrollable content
    div(
      idAttr := "nodes-menu",
      cls("hidden") <-- !isVisible(1),
      // List of nodes
      NodesList(state, filteredDiagramEvent(state, onlyActiveNodes.signal, filterNodesByNodeId.signal))
    ),
    // ------ TAB: 2 ------
    div(
      cls("hidden") <-- !isVisible(2),
      Join(LabeledCheckbox(id = s"filter-by-active", labelStr = "only active", isChecked = onlyActiveEdges)),
      Search(
        placeholder := "filter",
        controlled(value <-- filterEdgesByNodeId, onInput.mapToValue --> filterEdgesByNodeId)
      ).smallInput
    ),
    div(
      cls("hidden") <-- !isVisible(2),
      cls := "overflow-x-auto rounded-box bg-base-100",
      table(
        cls := "table table-xs table-pin-rows",
        thead(tr(th("Source"), th(""), th("Target"), th("Label"))),
        tbody(
          children <--
            state
              .fullGraph
              .combineWith(onlyActiveEdges, filterEdgesByNodeId.signal, state.hiddenNodesS)
              .map: (fullGraph, onlyActive, str, hiddenNodes) =>
                fullGraph
                  .orElse(!onlyActive, _.remove(hiddenNodes))
                  .filterArrowsBy(a => a.source.toString.contains(str) || a.target.toString.contains(str))
                  .toList
                  .sorted
              .map:
                _.map: arrow =>
                  tr(
                    cls := "whitespace-nowrap hover cursor-pointer",
                    cls("font-bold") <-- state.isEdgeVisible(arrow.nodeId),
                    cls("bg-base-200") <-- state.isSelected(arrow.nodeId),
                    td(cls := "truncate", cls("italic") <-- state.isSelected(arrow.source), arrow.source.toString),
                    td("→"),
                    td(cls := "truncate", cls("italic") <-- state.isSelected(arrow.target), arrow.target.toString),
                    td(cls := "truncate", cls("italic") <-- state.isSelected(arrow.target), arrow.label),
                    onClick.map(_.metaKey) --> state.diagramSelection.handleClickOnArrow(arrow),
                    onDblClick.preventDefault.stopPropagation --> { _ =>
                      state.toggleNode(arrow.source)
                      state.toggleNode(arrow.target)
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
    fullGraph.orElse(filter.isBlank, _.filterByNodeId(filter)).orElse(!onlyActive, _.remove(hiddenNodes))
