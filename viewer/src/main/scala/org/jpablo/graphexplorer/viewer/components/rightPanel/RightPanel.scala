package org.jpablo.graphexplorer.viewer.components.rightPanel

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import io.laminext.syntax.core.*
import org.jpablo.graphexplorer.viewer.backends.graphviz.DotExamples.examples
import org.jpablo.graphexplorer.viewer.components.attributes.views.{DefaultsView, StyleView}
import org.jpablo.graphexplorer.viewer.components.codeMirror.CodeMirror
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.*
import org.jpablo.graphexplorer.viewer.components.rightPanel.{EdgesList, NodesList}
import org.jpablo.graphexplorer.viewer.widgets.Icons.layoutSidebarReverseIcon

class RightPanel(state: ViewerState):
  private val visibleTab = state.rightPanelTabIndex
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
        idAttr := "right-panel",
        cls <-- state.rightPanelVisible.signal.map(if _ then "visible" else "not-visible"),
        styleAttr <-- state.rightPanelVisible.signal.map { visible =>
          if visible then
            "--right-panel-width: 24rem;"
          else
            "--right-panel-width: 0px;"
        },
        // Fixed header section
        div(
          idAttr := "right-panel-header",
          cls    := "flex-none",
          firstRow,
          // --- Tab Headers ---
          div(
            idAttr := "right-panel-tab-buttons",
            List(
              Button("Style").tiny,
              Button(child <-- state.fullGraph.map(_.summary.nodes).map(n => s"Nodes ($n)")).tiny,
              Button(child <-- state.fullGraph.map(_.summary.arrows).map(n => s"Arrows ($n)")).tiny,
              Button("Defaults").tiny,
              Button("Source").tiny,
            ).zipWithIndex.map: (child, idx) =>
              child.amend(cls("btn-active") <-- isVisible(idx), onClick --> visibleTab.set(idx))
          )
        ),
        // Scrollable content section
        div(
          idAttr := "right-panel-content",
          // --- Tab Body ---
          List(
            StyleView(state),
            NodesList(state, onlyActiveNodes),
            EdgesList(state, onlyActiveEdges),
            DefaultsView(state),
            TabSource,
          ).zipWithIndex.map: (child, idx) =>
            child.amend(cls := "h-full overflow-y-auto", cls("hidden") <-- !isVisible(idx))
        )
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

  private def TabSource =
    div(
      cls := "flex flex-col h-full",
      div(
        cls := "mb-4 flex-none",
        a(
          cls    := "link",
          href   := "https://www.graphviz.org/documentation/",
          target := "_blank",
          title  := "Visit the Graphviz documentation for more information",
          "Documentation"
        )
      ),
      div(
        cls := "flex-grow overflow-y-auto",
        CodeMirror(
          state,
          idAttr      := "nodes-source",
          placeholder := "DOT source"
        )
      )
    )
