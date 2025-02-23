package org.jpablo.graphexplorer.viewer.components.rightPanel

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import io.laminext.syntax.core.*
import org.jpablo.graphexplorer.viewer.backends.graphviz.DotExamples.examples
import org.jpablo.graphexplorer.viewer.components.attributes.StyleView
import org.jpablo.graphexplorer.viewer.components.codeMirror.CodeMirror
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.*
import org.jpablo.graphexplorer.viewer.components.rightPanel.{NodesList, EdgesList}
import org.jpablo.graphexplorer.viewer.widgets.Icons.layoutSidebarReverseIcon

class RightPanel(state: ViewerState):
  private val visibleTab = state.rightPanelTabIndex
  private val filterNodesByNodeId = Var("")
  private val filterEdgesByNodeId = Var("")
  private val onlyActiveNodes = Var(false)
  private val onlyActiveEdges = Var(false)

  private val inputId = s"toggle-diagram-elements"

  private def isVisible(i: Int) = visibleTab.signal.map(_ == i)

  def render() =
    div(
      // -------- Style Panel Toggle --------
      Tooltip(
        text = "Style",
        cls := "flex-none tooltip-bottom absolute right-2 top-2 z-20",
        input(idAttr := inputId, tpe := "checkbox", cls := "drawer-toggle"),
        label(
          forId := inputId,
          cls("btn-active") <-- state.rightPanelVisible,
          onClick --> state.rightPanelVisible.toggle()
        ).asBtn.tiny.layoutSidebarReverseIcon
      ),

      div(
        idAttr := "nodes-panel",
        cls <--
          state.rightPanelVisible.signal.map(if _ then "p-2 gap-3 opacity-100 visible" else "w-0 p-0 gap-0 opacity-0 invisible"),
        firstRow,
        // --- Tab Headers ---
        div(
          idAttr := "nodes-panel-tab-buttons",
          tabHeaderStyle(0),
          tabHeaderNodes(1),
          tabHeaderEdges(2),
          tabHeaderSource(3)
        ),
        // --- Tab Body ---
        tabStyle(0),
        tabNodes(1),
        tabEdges(2),
        tabSource(3)
      )
    )

  private def firstRow =
    div(
      cls := "flex gap-2 justify-between",
      Select(
        placeholderText = "Select example",
        options         = examples.keys.map(name => name -> name),
        onChange.mapToValue.map(examples).flatMap(FetchStream.get(_)) --> { source =>
          state.showAllNodes()
          state.sourceText.set(source)
        }
      ),
      a(
        cls    := "link mr-10",
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
      child <-- state.fullGraph.map(_.summary.arrows).map(n => s"Arrows ($n)"),
      cls("btn-active") <-- isVisible(idx),
      onClick --> visibleTab.set(idx)
    ).tiny

  private def tabStyle(idx: Int) =
    StyleView(state).amend(cls("hidden") <-- !isVisible(idx))

  private def tabSource(idx: Int) =
    CodeMirror(
      state,
      idAttr := "nodes-source",
      cls("hidden") <-- !isVisible(idx),
      placeholder := "DOT source"
    )

  private def tabNodes(idx: Int) =
    div(
      cls("hidden") <-- !isVisible(idx),
      form(
        idAttr := "nodes-panel-controls",
        Join(LabeledCheckbox(id = s"filter-by-active", labelStr = "only visible", isChecked = onlyActiveNodes)),
        Search(
          placeholder := "filter",
          controlled(value <-- filterNodesByNodeId, onInput.mapToValue --> filterNodesByNodeId)
        ).smallInput
      ),
      div(
        idAttr := "nodes-panel-contents",
        NodesList(state, onlyActiveNodes.signal, filterNodesByNodeId.signal)
      )
    )

  private def tabEdges(idx: Int) =
    div(
      cls("hidden") <-- !isVisible(idx),
      form(
        idAttr := "edges-panel-controls",
        Join(LabeledCheckbox(id = s"filter-by-active", labelStr = "only visible", isChecked = onlyActiveEdges)),
        Search(
          placeholder := "filter",
          controlled(value <-- filterEdgesByNodeId, onInput.mapToValue --> filterEdgesByNodeId)
        ).smallInput
      ),
      div(
        idAttr := "edges-panel-contents",
        EdgesList(state, onlyActiveEdges, filterEdgesByNodeId.signal)
      )
    )
