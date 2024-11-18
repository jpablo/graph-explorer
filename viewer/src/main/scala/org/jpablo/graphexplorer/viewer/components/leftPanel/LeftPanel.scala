package org.jpablo.graphexplorer.viewer.components.leftPanel

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import io.laminext.syntax.core.*
import org.jpablo.graphexplorer.viewer.backends.graphviz.DotExamples.examples
import org.jpablo.graphexplorer.viewer.components.attributes.DiagramAttributesView
import org.jpablo.graphexplorer.viewer.components.codeMirror.CodeMirror
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.*

def LeftPanel(state: ViewerState) =
  val visibleTab = state.leftPanelTabIndex
  val filterNodesByNodeId = Var("")
  val filterEdgesByNodeId = Var("")
  val onlyActiveNodes = Var(false)
  val onlyActiveEdges = Var(false)

  def isVisible(i: Int) = visibleTab.signal.map(_ == i)

  div(
    idAttr := "nodes-panel",
    FirstRow(state),
    // --- Tab Headers ---
    div(
      idAttr := "nodes-panel-tab-buttons",
      TabHeaderStyle(visibleTab, isVisible, 0),
      TabHeaderSource(visibleTab, isVisible, 1),
      TabHeaderNodes(visibleTab, state, isVisible, 2),
      TabHeaderEdges(visibleTab, state, isVisible, 3)
    ),
    // --- Tab Body ---
    TabStyle(state, isVisible, 0),
    TabSource(state, isVisible, 1),
    TabNodes(state, filterNodesByNodeId, onlyActiveNodes, isVisible, 2),
    TabEdges(state, filterEdgesByNodeId, onlyActiveEdges, isVisible, 3)
  )

def FirstRow(state: ViewerState) =
  div(
    cls := "flex gap-2 justify-between ml-10",
    Select(
      placeholderText = "Select example",
      options = examples.keys.map(name => name -> name),
      onChange.mapToValue.map(examples).flatMap(FetchStream.get(_)) --> { source =>
        state.showAllNodes()
        state.sourceText.set(source)
      }
    ),
    a(
      cls := "link",
      href := "https://www.graphviz.org/documentation/",
      target := "_blank",
      title := "Visit the Graphviz documentation for more information",
      "Graphviz"
    )
  )

def TabHeaderStyle(visibleTab: Var[Int], isVisible: Int => Signal[Boolean], idx: Int) =
  Button("Style", cls("btn-active") <-- isVisible(idx), onClick --> visibleTab.set(idx)).tiny

def TabHeaderSource(visibleTab: Var[Int], isVisible: Int => Signal[Boolean], idx: Int) =
  Button("Source", cls("btn-active") <-- isVisible(idx), onClick --> visibleTab.set(idx)).tiny

def TabHeaderNodes(visibleTab: Var[Int], state: ViewerState, isVisible: Int => Signal[Boolean], idx: Int) =
  Button(
    child <-- state.fullGraph.map(_.summary.nodes).map(n => s"Nodes ($n)"),
    cls("btn-active") <-- isVisible(idx),
    onClick --> visibleTab.set(idx)
  ).tiny

def TabHeaderEdges(visibleTab: Var[Int], state: ViewerState, isVisible: Int => Signal[Boolean], idx: Int) =
  Button(
    child <-- state.fullGraph.map(_.summary.arrows).map(n => s"Edges ($n)"),
    cls("btn-active") <-- isVisible(idx),
    onClick --> visibleTab.set(idx)
  ).tiny

def TabStyle(state: ViewerState, isVisible: Int => Signal[Boolean], idx: Int) =
  div(
    cls("hidden") <-- !isVisible(idx),
    DiagramAttributesView(state)
  )

def TabSource(state: ViewerState, isVisible: Int => Signal[Boolean], idx: Int) =
  CodeMirror(
    state.sourceText,
    idAttr := "nodes-source",
    cls("hidden") <-- !isVisible(idx),
    placeholder := "DOT source"
  )

def TabNodes(
    state:               ViewerState,
    filterNodesByNodeId: Var[String],
    onlyActiveNodes:     Var[Boolean],
    isVisible:           Int => Signal[Boolean],
    idx:                 Int
) =
  div(
    form(
      idAttr := "nodes-panel-controls",
      cls("hidden") <-- !isVisible(idx),
      Join(LabeledCheckbox(id = s"filter-by-active", labelStr = "only visible", isChecked = onlyActiveNodes)),
      Search(
        placeholder := "filter",
        controlled(value <-- filterNodesByNodeId, onInput.mapToValue --> filterNodesByNodeId)
      ).smallInput
    ),
    div(
      idAttr := "nodes-panel-contents",
      cls("hidden") <-- !isVisible(idx),
      NodesList(state, onlyActiveNodes.signal, filterNodesByNodeId.signal)
    )
  )

def TabEdges(
    state:               ViewerState,
    filterEdgesByNodeId: Var[String],
    onlyActiveEdges:     Var[Boolean],
    isVisible:           Int => Signal[Boolean],
    idx:                 Int
) =
  div(
    form(
      idAttr := "edges-panel-controls",
      cls("hidden") <-- !isVisible(idx),
      Join(LabeledCheckbox(id = s"filter-by-active", labelStr = "only visible", isChecked = onlyActiveEdges)),
      Search(
        placeholder := "filter",
        controlled(value <-- filterEdgesByNodeId, onInput.mapToValue --> filterEdgesByNodeId)
      ).smallInput
    ),
    div(
      idAttr := "edges-panel-contents",
      cls("hidden") <-- !isVisible(idx),
      EdgesList(state, onlyActiveEdges, filterEdgesByNodeId.signal)
    )
  )
