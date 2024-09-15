package org.jpablo.typeexplorer.viewer.components.nodes

import com.raquo.laminar.api.L.*
import com.softwaremill.quicklens.*
import io.laminext.syntax.core.*
import org.jpablo.typeexplorer.viewer.extensions.*
import org.jpablo.typeexplorer.viewer.graph.ViewerGraph
import org.jpablo.typeexplorer.viewer.state.{PackagesOptions, Project, ViewerState, VisibleNodes}
import org.jpablo.typeexplorer.viewer.widgets.*
import com.raquo.laminar.api.features.unitArrows

def LeftPanel(state: ViewerState) =
  val visibleTab = state.sideBarTabIndex
  val showOptions = Var(false)
  val filterByNodeId = Var("")
  val visibleNodes = state.visibleNodes.signal
  def isVisible(i: Int) = visibleTab.signal.map(_ == i)
  val filteredGraph: Signal[ViewerGraph] =
    filteredDiagramEvent(state, visibleNodes, filterByNodeId.signal)
  div(
    idAttr := "nodes-panel",
    div(
      idAttr := "nodes-panel-tab-buttons",
      Button("Source", cls("btn-active") <-- isVisible(0), onClick --> visibleTab.set(0)).tiny,
      Button("Nodes", cls("btn-active") <-- isVisible(1), onClick --> visibleTab.set(1)).tiny
    ),

    textArea(
      idAttr := "nodes-source",
      cls    := "textarea textarea-bordered",
      cls("hidden") <-- !isVisible(0),
      placeholder := "Replace source",
      controlled(value <-- state.source, onInput.mapToValue --> state.source)
    ),

    // --- controls ---
    form(
      idAttr := "nodes-panel-controls",
      cls("hidden") <-- !isVisible(1),
      LabeledCheckbox(
        "show-options-toggle",
        "options",
        showOptions.signal,
        clickHandler = Observer(_ => showOptions.update(!_)),
        toggle       = true
      ),
      showOptions.signal.childWhenTrue:
        Options(state)
      ,
      Search(
        placeholder := "filter",
        controlled(
          value <-- filterByNodeId,
          onInput.mapToValue --> filterByNodeId
        )
      ).smallInput
    ),
    // Scrollable content
    div(
      idAttr := "nodes-menu",
      cls("hidden") <-- !isVisible(1),
      // List of nodes
      NodesList(state, filteredGraph)
    )
  )

private def filteredDiagramEvent(
    state:          ViewerState,
    visibleNodes:   Signal[VisibleNodes],
    filterByNodeId: Signal[String]
): Signal[ViewerGraph] =
  state.fullGraph
    .combineWith(
      state.project.packagesOptions,
      filterByNodeId,
      // TODO: consider another approach where changing activeSymbols does not trigger
      // a full tree redraw, but just modifies the relevant nodes
      visibleNodes
    )
//    .changes
//    .debounce(300)
    .map:
      (
          graph:           ViewerGraph,
          packagesOptions: PackagesOptions,
          w:               String,
          visibleNodes:    VisibleNodes
      ) =>
        graph
          .orElse(w.isBlank, _.filterByNodeId(w))
          .orElse(!packagesOptions.onlyActive, _.subgraph(visibleNodes.keySet))

private def Options(state: ViewerState) =
  div(
    cls := "card card-compact p-1 m-2 mb-2 border-slate-300 border-[1px]",
    div(
      cls := "card-body p-1",
      LabeledCheckbox(
        id        = s"filter-by-active",
        labelStr  = "only active",
        isChecked = state.project.packagesOptions.map(_.onlyActive),
        clickHandler = Observer: _ =>
          state.project.update(_.modify(_.packagesOptions.onlyActive).using(!_)),
        toggle = true
      )
    )
  )
