package org.jpablo.graphexplorer.viewer.components.rightPanel

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.codeMirror.CodeMirror
import org.jpablo.graphexplorer.viewer.state.ViewerState

def SourceTab(state: ViewerState) =
  div(
    idAttr := "source-tab",
    cls("border-error border") <-- state.editorError.signal.map(_.isDefined),
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
    ),
    child.maybe <-- state.editorError.signal.map:
      _.map: msg =>
        div(role := "alert", cls := "m-1 mt-2 p-1 rounded-md flex-none alert alert-error text-sm", span(msg))
  )
