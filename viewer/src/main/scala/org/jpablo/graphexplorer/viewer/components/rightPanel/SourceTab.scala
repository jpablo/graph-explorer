package org.jpablo.graphexplorer.viewer.components.rightPanel

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.codeMirror.CodeMirror
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.state.ViewerState

def SourceTab(state: ViewerState) =
  // Presentation metadata for the currently selected format, resolved via the backend registry.
  val currentInfo = state.formatSelection.signal.map(state.formatInfo)

  div(
    idAttr := "source-tab",
    cls("border-error border") <-- state.editorNotice.signal.map(_.exists(_.isError)),
    div(
      cls := "m-2 flex-none",
      div(
        cls := "flex items-center gap-3 flex-wrap",
        select(
          cls := "select select-sm",
          state.availableFormats.map: (format, info) =>
            option(
              value := format.toString,
              info.selectorLabel
            ),
          controlled(
            value <-- state.formatSelection.signal.map(_.toString),
            onChange.mapToValue.map(DiagramFormat.valueOf) --> state.setDiagramFormat
          )
        ),
        a(
          cls    := "link",
          href   <-- currentInfo.map(_.documentationUrl),
          target := "_blank",
          title  <-- currentInfo.map(_.documentationTitle),
          "Documentation"
        )
      )
    ),
    div(
      cls := "flex-grow overflow-y-auto",
      CodeMirror(
        state,
        idAttr      := "nodes-source",
        placeholder <-- currentInfo.map(_.editorPlaceholder)
      )
    ),
    child.maybe <-- state.editorNotice.signal.map:
      _.map: notice =>
        val levelCls = if notice.isError then "alert-error" else "alert-info"
        div(role := "alert", cls := s"m-1 mt-2 p-1 rounded-md flex-none alert $levelCls text-sm", span(notice.message))
  )
