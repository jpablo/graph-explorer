package org.jpablo.graphexplorer.viewer.components.nodes

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import com.softwaremill.quicklens.*
import io.laminext.syntax.core.*
import org.jpablo.graphexplorer.viewer.extensions.*
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.state.{Project, ViewerState}
import org.jpablo.graphexplorer.viewer.widgets.*
import org.scalajs.dom

def LeftPanel(state: ViewerState) =
  val visibleTab = state.sideBarTabIndex
  val showOptions = Var(false)
  val filterByNodeId = Var("")
  def isVisible(i: Int) = visibleTab.signal.map(_ == i)
  val filteredGraph =
    filteredDiagramEvent(state, filterByNodeId.signal)
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
      placeholder := "DOT source",
      value <-- state.source,
      onInput(_.debounce(300)) --> { (e: dom.Event) =>
        state.source.set(e.target.asInstanceOf[dom.html.TextArea].value)
      }
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
        controlled(value <-- filterByNodeId, onInput.mapToValue --> filterByNodeId)
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
