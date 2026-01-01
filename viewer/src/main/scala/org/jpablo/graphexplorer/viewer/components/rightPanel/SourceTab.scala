package org.jpablo.graphexplorer.viewer.components.rightPanel

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.codeMirror.CodeMirror
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.state.ViewerState

def SourceTab(state: ViewerState) =
  val formatChoices = Seq(
    DiagramFormat.Mermaid -> "MermaidJS",
    DiagramFormat.DOT     -> "Graphviz (DOT)"
  )

  val documentationLink = state.formatSelection.signal.map:
    case DiagramFormat.Mermaid =>
      ("https://mermaid.js.org/intro/", "Visit the Mermaid documentation for more information")
    case DiagramFormat.DOT =>
      ("https://www.graphviz.org/documentation/", "Visit the Graphviz documentation for more information")

  div(
    idAttr := "source-tab",
    cls("border-error border") <-- state.editorError.signal.map(_.isDefined),
    div(
      cls := "m-2 flex-none",
      div(
        cls := "flex items-center gap-3 flex-wrap",
        select(
          cls := "select select-sm",
          formatChoices.map: (format, label) =>
            option(
              value := format.toString,
              label
            ),
          controlled(
            value <-- state.formatSelection.signal.map(_.toString),
            onChange.mapToValue.map(DiagramFormat.valueOf) --> state.setDiagramFormat
          )
        ),
        a(
          cls    := "link",
          href   <-- documentationLink.map(_._1),
          target := "_blank",
          title  <-- documentationLink.map(_._2),
          "Documentation"
        )
      )
    ),
    div(
      cls := "flex-grow overflow-y-auto",
      CodeMirror(
        state,
        idAttr      := "nodes-source",
        placeholder <-- state.formatSelection.signal.map:
          case DiagramFormat.Mermaid => "Mermaid source"
          case DiagramFormat.DOT     => "DOT source"
      )
    ),
    child.maybe <-- state.editorError.signal.map:
      _.map: msg =>
        div(role := "alert", cls := "m-1 mt-2 p-1 rounded-md flex-none alert alert-error text-sm", span(msg))
  )
