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
  val visibleTab = Var(0)
  val showOptions = Var(false)
  val filterByNodeId = Var("")
  val visibleNodes = state.visibleNodes.signal
  val filteredGraph: Signal[ViewerGraph] =
    filteredDiagramEvent(state, visibleNodes, filterByNodeId.signal)
  div(
    cls    := "bg-base-100 p-1 z-10 w-96 flex-shrink-0 h-full flex flex-col overflow-x-hidden",
    idAttr := "nodes-panel",
    div(
      Button("Source", onClick --> visibleTab.set(0)).tiny,
      Button("Nodes", onClick --> visibleTab.set(1)).tiny
    ),

    textArea(
      idAttr := "nodes-source",
      cls    := "textarea textarea-bordered whitespace-nowrap w-full",
      cls("hidden") <-- visibleTab.signal.map(_ != 0),
      placeholder := "Replace source",
      controlled(value <-- state.source, onInput.mapToValue --> state.source)
    ),

    // --- controls ---
    form(
      idAttr := "nodes-panel-controls",
      cls    := "bg-base-100 z-20 pb-2",
      cls("hidden") <-- visibleTab.signal.map(_ != 1),
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
      cls    := "overflow-y-auto flex-grow",
      cls("hidden") <-- visibleTab.signal.map(_ != 1),
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
//          .subgraphByKinds(packagesOptions.kinds)
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
//      hr(),
      // TODO: this is not working, fix it
//      children <--
//        state.fullGraph
//          .map(_.kinds)
//          .map: kinds =>
//            for kind <- kinds.toList
//            yield LabeledCheckbox(
//              id = s"show-ns-kind-$kind",
//              kind.toString,
//              isChecked = state.project.packagesOptions
//                .map(_.kinds)
//                .map(_.contains(kind)),
//              clickHandler = Observer: b =>
//                state.project.update(_.modify(_.packagesOptions.kinds).using(_.toggleWith(kind, b)))
//            )
    )
  )
