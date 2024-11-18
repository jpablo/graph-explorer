package org.jpablo.graphexplorer.viewer.components.leftPanel

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import io.laminext.syntax.core.*
import org.jpablo.graphexplorer.viewer.backends.graphviz.DotExamples.examples
import org.jpablo.graphexplorer.viewer.components.attributes.DiagramAttributesView
import org.jpablo.graphexplorer.viewer.components.codeMirror.CodeMirror
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.*

class LeftPanel(state: ViewerState):
  private val visibleTab = state.leftPanelTabIndex
  private val filterNodesByNodeId = Var("")
  private val filterEdgesByNodeId = Var("")
  private val onlyActiveNodes = Var(false)
  private val onlyActiveEdges = Var(false)

  private def isVisible(i: Int) = visibleTab.signal.map(_ == i)

  def render() =
    div(
      idAttr := "nodes-panel",
      firstRow,
      // --- Tab Headers ---
      div(
        idAttr := "nodes-panel-tab-buttons",
        tabHeaderStyle(0),
        tabHeaderSource(1),
        tabHeaderNodes(2),
        tabHeaderEdges(3)
      ),
      // --- Tab Body ---
      tabStyle(0),
      tabSource(1),
      tabNodes(2),
      tabEdges(3)
    )

  private def firstRow =
    div(
      cls := "flex gap-2 justify-between ml-10",
      Select(
        placeholderText = "Select example",
        options         = examples.keys.map(name => name -> name),
        onChange.mapToValue.map(examples).flatMap(FetchStream.get(_)) --> { source =>
          state.showAllNodes()
          state.sourceText.set(source)
        }
      ),
      a(
        cls    := "link",
        href   := "https://www.graphviz.org/documentation/",
        target := "_blank",
        title  := "Visit the Graphviz documentation for more information",
        "Graphviz"
      )
    )

  private def tabHeaderStyle(idx: Int) =
    Button("Style", cls("btn-active") <-- isVisible(idx), onClick --> visibleTab.set(idx)).tiny

  private def tabHeaderSource(idx: Int) =
    Button("Source", cls("btn-active") <-- isVisible(idx), onClick --> visibleTab.set(idx)).tiny

  private def tabHeaderNodes(idx: Int) =
    Button(
      child <-- state.fullGraph.map(_.summary.nodes).map(n => s"Nodes ($n)"),
      cls("btn-active") <-- isVisible(idx),
      onClick --> visibleTab.set(idx)
    ).tiny

  private def tabHeaderEdges(idx: Int) =
    Button(
      child <-- state.fullGraph.map(_.summary.arrows).map(n => s"Edges ($n)"),
      cls("btn-active") <-- isVisible(idx),
      onClick --> visibleTab.set(idx)
    ).tiny

  private def tabStyle(idx: Int) =
    div(
      cls("hidden") <-- !isVisible(idx),
      DiagramAttributesView(state)
    )

  private def tabSource(idx: Int) =
    CodeMirror(
      state.sourceText,
      idAttr := "nodes-source",
      cls("hidden") <-- !isVisible(idx),
      placeholder := "DOT source"
    )

  private def tabNodes(idx: Int) =
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

  private def tabEdges(idx: Int) =
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
