package org.jpablo.graphexplorer.viewer.components.leftPanel

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import io.laminext.syntax.core.*
import org.jpablo.graphexplorer.viewer.backends.graphviz.DotExamples.examples
import org.jpablo.graphexplorer.viewer.components.codeMirror.CodeMirror
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
    // --- Tab Headers ---
    div(
      cls := "flex gap-2",
      select(
        cls := "select select-bordered select-xs max-w-xs",
        option("Select example", disabled := true, selected := true),
        examples.keys.map(name => option(name, value := name)),
        onChange.mapToValue.map(examples).flatMap(FetchStream.get(_)) --> { source =>
          state.showAllNodes()
          state.source.set(source)
        }
      ),
      a(
        cls    := "link",
        href   := "https://www.graphviz.org/documentation/",
        target := "_blank",
        title  := "Visit the Graphviz documentation for more information",
        "Graphviz"
      )
    ),
    div(
      idAttr := "nodes-panel-tab-buttons",
      // Header 1: Source
      Button("Source", cls("btn-active") <-- isVisible(0), onClick --> visibleTab.set(0)).tiny,
      // Header 2: Nodes
      Button(
        child <-- state.fullGraph.map(_.summary.nodes).map(n => s"Nodes ($n)"),
        cls("btn-active") <-- isVisible(1),
        onClick --> visibleTab.set(1)
      ).tiny,
      // Header 3: Edges
      Button(
        child <-- state.fullGraph.map(_.summary.arrows).map(n => s"Edges ($n)"),
        cls("btn-active") <-- isVisible(2),
        onClick --> visibleTab.set(2)
      ).tiny
    ),
    // ------ TAB 0: Source ------
    // --- DOT sources ---
    CodeMirror(
      state.source,
      idAttr := "nodes-source",
      cls("hidden") <-- !isVisible(0),
      placeholder := "DOT source"
    ),

    // ------ TAB 1: Nodes ------
    // --- controls ---
    form(
      idAttr := "nodes-panel-controls",
      cls("hidden") <-- !isVisible(1),
      Join(LabeledCheckbox(id = s"filter-by-active", labelStr = "only visible", isChecked = onlyActiveNodes)),
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
      NodesList(state, onlyActiveNodes.signal, filterNodesByNodeId.signal)
    ),
    // ------ TAB 2: Edges ------
    div(
      cls("hidden") <-- !isVisible(2),
      Join(LabeledCheckbox(id = s"filter-by-active", labelStr = "only visible", isChecked = onlyActiveEdges)),
      Search(
        placeholder := "filter",
        controlled(value <-- filterEdgesByNodeId, onInput.mapToValue --> filterEdgesByNodeId)
      ).smallInput
    ),
    div(
      cls("hidden") <-- !isVisible(2),
      cls := "overflow-x-auto rounded-box bg-base-100",
      EdgesList(state, onlyActiveEdges, filterEdgesByNodeId.signal)
    )
  )
