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
    cls := "bg-base-100 rounded-box overflow-auto p-1 z-10 w-64 flex-shrink-0 overflow-y-auto h-full",
    idAttr := "nodes-panel",
    // --- controls ---
    form(
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
//        focus <-- viewerState.appConfigDialogOpenV.signal.changes,
        controlled(
          value <-- filterByNodeId,
          onInput.mapToValue --> filterByNodeId
        )
//        onKeyDown.filter(e => e.key == "Enter" || e.key == "Escape") --> viewerState.appConfigDialogOpenV.set(false)
      ).smallInput
    ),
    div(
      cls := "overflow-auto mt-1",
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
      ),
      hr(),
      // TODO: this is not working, fix it
      children <--
        state.fullGraph
          .map(_.kinds)
          .map: kinds =>
            for kind <- kinds.toList
            yield LabeledCheckbox(
              id = s"show-ns-kind-$kind",
              kind.toString,
              isChecked = state.project.packagesOptions
                .map(_.kinds)
                .map(_.contains(kind)),
              clickHandler = Observer: b =>
                state.project.update(_.modify(_.packagesOptions.kinds).using(_.toggleWith(kind, b)))
            )
    )
  )
