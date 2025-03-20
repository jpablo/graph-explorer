package org.jpablo.graphexplorer.viewer.components.rightPanel

import com.raquo.laminar.api.L.*
import io.laminext.syntax.core.*
import org.jpablo.graphexplorer.viewer.components.attributes.views.{DefaultsView, ElementsView, StyleView}
import org.jpablo.graphexplorer.viewer.components.codeMirror.CodeMirror
import org.jpablo.graphexplorer.viewer.state.ViewerState

class RightPanel(state: ViewerState):
  private val visibleTab = state.rightPanelTabIndex

  private def isVisible(i: Int) = visibleTab.signal.map(_ == i)

  def render() =
    div(
      // -------- Style Panel Toggle --------
      div(
        idAttr := "right-panel",
        cls <-- state.rightPanelVisible.signal.map(if _ then "visible" else "not-visible"),
        // Scrollable content section
        div(
          idAttr := "right-panel-content",
          // --- Tab Body ---
          List(
            StyleView(state),
            DefaultsView(state),
            ElementsView(state),
            SourceTab
          ).zipWithIndex.map: (child, idx) =>
            child.amend(cls := "h-full overflow-y-auto", cls("hidden") <-- !isVisible(idx))
        )
      )
    )

  private def SourceTab =
    div(
      idAttr := "source-tab",
      cls    := "flex flex-col h-full",
      div(
        cls := "m-2 flex-none",
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
