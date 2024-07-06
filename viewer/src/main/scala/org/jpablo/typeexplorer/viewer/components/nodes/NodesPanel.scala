package org.jpablo.typeexplorer.viewer.components.nodes

import com.raquo.laminar.api.L.*
import com.softwaremill.quicklens.*
import io.laminext.syntax.core.*
import org.jpablo.typeexplorer.viewer.extensions.*
import org.jpablo.typeexplorer.viewer.graph.ViewerGraph
import org.jpablo.typeexplorer.viewer.state.{PackagesOptions, Project, ViewerState, VisibleNodes}
import org.jpablo.typeexplorer.viewer.widgets.*

def NodesPanel(state: ViewerState) =
  val showOptions = Var(false)
  val filterByNodeId = Var("")
  val visibleNodes = state.visibleNodes.signal
  val filteredGraph: Signal[ViewerGraph] =
    filteredDiagramEvent(state, visibleNodes, filterByNodeId.signal)
  div(
//    cls := "bg-base-100 rounded-box overflow-auto p-1 z-10 w-64 flex-shrink-0 overflow-y-auto h-full",
//    cls    := "bg-base-100 rounded-box p-1 z-10 w-64 flex-shrink-0 h-full flex flex-col",
    cls    := "bg-base-100 rounded-box p-1 z-10 w-64 flex-shrink-0 h-full flex flex-col overflow-x-hidden",
    idAttr := "nodes-panel",

    // Tabs
    div(
      role := "tablist",
      cls  := "tabs tabs-lifted tabs-xs",

      // Graph Tab
      input(
        typ        := "radio",
        nameAttr   := "nodes_panel_tabs",
        role       := "tab",
        cls        := "tab",
        aria.label := "Source"
      ),
      div(
        role := "tabpanel",
        cls  := "tab-content bg-base-100 border-base-300 rounded-box p-2",
        textArea(
//          cls         := "textarea textarea-bordered whitespace-nowrap w-full h-full",
          cls := "textarea textarea-bordered whitespace-nowrap w-full h-[calc(100vh-6rem)]",
          placeholder := "Replace source"
        )
      ),

      // Nodes Tab
      input(
        typ            := "radio",
        nameAttr       := "nodes_panel_tabs",
        role           := "tab",
        cls            := "tab",
        aria.label     := "Nodes",
        defaultChecked := true
      ),
      div(
        role := "tabpanel",
        cls  := "tab-content bg-base-100 border-base-300 rounded-box p-2",
        // --- controls ---
        form(
          idAttr := "nodes-panel-controls",
          cls    := "sticky top-0 bg-base-100 z-20 pb-2",
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
          cls := "overflow-y-auto flex-grow",
          // List of nodes
          div(
            cls := "overflow-auto mt-1",
            NodesList(state, filteredGraph)
          )
        )
      )
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
