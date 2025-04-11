package org.jpablo.graphexplorer.viewer.components.rightPanel

import com.raquo.laminar.api.L.*
import io.laminext.syntax.core.*
import org.jpablo.graphexplorer.viewer.components.attributes.views.{DiagramAttributesView, ElementsView}
import org.jpablo.graphexplorer.viewer.components.codeMirror.CodeMirror
import org.jpablo.graphexplorer.viewer.state.ViewerState

class RightPanel(state: ViewerState):
  private def isVisible(i: Int) = state.rightPanelTabIndex.signal.map(_ == i)

  val isFloating = state.rightPanelTabIndex.signal.map(_ == 0)

  def render() =
    div(
      idAttr := "right-panel",
      cls <-- state.rightPanelVisible.signal.map(if _ then "visible" else "not-visible"),
      cls("floating card card-xs") <-- isFloating,
      cls("not-floating") <-- isFloating.not,
      div(
        idAttr := "right-panel-content",
        cls("card-body") <-- isFloating,
        List(
          DiagramAttributesView(state),
          ElementsView(state),
          SourceTab
        ).zipWithIndex.map: (child, idx) =>
          child.amend(cls := "h-full max-h-full flex flex-col", cls("hidden") <-- !isVisible(idx))
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
