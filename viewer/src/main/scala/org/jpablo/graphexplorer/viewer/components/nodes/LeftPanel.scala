package org.jpablo.graphexplorer.viewer.components.nodes

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import com.softwaremill.quicklens.*
import io.laminext.syntax.core.*
import org.jpablo.graphexplorer.viewer.backends.graphviz.DotExamples
import org.jpablo.graphexplorer.viewer.backends.graphviz.DotExamples.examples
import org.jpablo.graphexplorer.viewer.extensions.*
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.state.{Project, ViewerState}
import org.jpablo.graphexplorer.viewer.widgets.*

def LeftPanel(state: ViewerState) =
  val visibleTab = state.leftPanelTabIndex
  val filterNodesByNodeId = Var("")
  val filterEdgesByNodeId = Var("")
  def isVisible(i: Int) = visibleTab.signal.map(_ == i)
  val filteredGraph = filteredDiagramEvent(state, filterNodesByNodeId.signal)

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
      Options(state),
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
      NodesList(state, filteredGraph)
    ),
    // ------ TAB: 2 ------
    div(
      cls("hidden") <-- !isVisible(2),
      Search(
        placeholder := "filter",
        controlled(value <-- filterEdgesByNodeId, onInput.mapToValue --> filterEdgesByNodeId)
      ).smallInput,
      ul(
        cls := "menu menu-sm bg-base-200 rounded-box",
        children <-- state.fullGraph
          .combineWith(filterEdgesByNodeId.signal)
          .map((g, str) => g.filterArrowsBy(a => a.source.toString.contains(str) || a.target.toString.contains(str)))
          .map(_.toList.sortBy(a => (a.source.toString, a.target.toString)))
          .map:
            _.map: arrow =>
              li(
                a(
                  idAttr := arrow.nodeId.toString,
                  cls    := "cursor-pointer",
                  div(
                    cls   := "truncate",
                    cls   := "truncate",
                    title := s"${arrow.source} → ${arrow.target}",
                    s"${arrow.source} → ${arrow.target}"
                  ),
                  onClick.preventDefault.stopPropagation --> state.diagramSelection
                    .set(arrow.source, arrow.target, arrow.nodeId)
                )
              )
      )
    )
  )

private def filteredDiagramEvent(
    state:          ViewerState,
    filterByNodeId: Signal[String]
): Signal[ViewerGraph] =
  state.fullGraph
    .combineWith(
      state.project.packagesOptions,
      filterByNodeId,
      state.hiddenNodesS
    )
    .map: (fullGraph, packagesOptions, filter, hiddenNodes) =>
      fullGraph
        .orElse(filter.isBlank, _.filterByNodeId(filter))
        .orElse(!packagesOptions.onlyActive, _.remove(hiddenNodes))

private def Options(state: ViewerState) =
  Join(
    LabeledCheckbox(
      id        = s"filter-by-active",
      labelStr  = "only active",
      isChecked = state.project.packagesOptions.map(_.onlyActive),
      clickHandler = Observer: _ =>
        state.project.update(_.modify(_.packagesOptions.onlyActive).using(!_))
    )
  )
