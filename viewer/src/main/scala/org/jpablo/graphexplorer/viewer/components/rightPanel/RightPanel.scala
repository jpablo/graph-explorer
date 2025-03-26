package org.jpablo.graphexplorer.viewer.components.rightPanel

import com.raquo.laminar.api.L.*
import io.laminext.syntax.core.*
import org.jpablo.graphexplorer.viewer.components.attributes.views.{DefaultsView, DiagramAttributesView, ElementsView}
import org.jpablo.graphexplorer.viewer.components.codeMirror.CodeMirror
import org.jpablo.graphexplorer.viewer.state.ViewerState

class RightPanel(state: ViewerState):
  private val visibleTab = state.rightPanelTabIndex

  private def isVisible(i: Int) = visibleTab.signal.map(_ == i)

  def render() =
    div(
      idAttr := "right-panel",
      cls <-- state.rightPanelVisible.signal.map(if _ then "visible" else "not-visible"),
      div(
        idAttr := "right-panel-content",
        List(
          DiagramAttributesView(state),
          DefaultsView(state),
          ElementsView(state),
          SourceTab
        ).zipWithIndex.map: (child, idx) =>
          child.amend(cls := "h-full flex flex-col", cls("hidden") <-- !isVisible(idx))
      )
    )

  private def SourceTab =
    div(
      idAttr := "source-tab",
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
