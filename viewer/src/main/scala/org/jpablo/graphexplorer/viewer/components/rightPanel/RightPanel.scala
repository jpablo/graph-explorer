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
          state.rightPanelVisible.signal.map(if _ then "p-2 gap-3 opacity-100 visible"
          else "w-0 p-0 gap-0 opacity-0 invisible"),
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
    div(
      cls := "flex flex-col h-full",
      cls("hidden") <-- !isVisible(idx),
      div(
        cls := "mb-4",
        a(
          cls    := "link",
          href   := "https://www.graphviz.org/documentation/",
          target := "_blank",
          title  := "Visit the Graphviz documentation for more information",
          "Documentation"
        )
      ),
      CodeMirror(
        state,
        idAttr := "nodes-source",
        placeholder := "DOT source"
      )
    )

  private def tabNodes(idx: Int) =
    NodesList(state, onlyActiveNodes, filterNodesByNodeId)
      .amend(cls("hidden") <-- !isVisible(idx))

  private def tabEdges(idx: Int) =
    EdgesList(state, onlyActiveEdges, filterEdgesByNodeId)
      .amend(cls("hidden") <-- !isVisible(idx))
