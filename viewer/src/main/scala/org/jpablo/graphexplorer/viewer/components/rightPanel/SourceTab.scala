package org.jpablo.graphexplorer.viewer.components.rightPanel

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.components.codeMirror.CodeMirror
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.Tooltip

def SourceTab(state: ViewerState) =
  // Presentation metadata for the currently selected format, resolved via the backend registry.
  val currentInfo = state.formatSelection.signal.map(state.formatInfo)

  div(
    idAttr := "source-tab",
    cls("border-error border") <-- state.editorNotice.signal.map(_.exists(_.isError)),
    div(
      cls := "source-toolbar",
      // Ghost select: the chevron carries the affordance, so the control doesn't need a box
      // competing with the editor beneath it.
      select(
        cls := "select select-ghost select-xs source-format",
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
      div(
        cls := "flex items-center gap-0.5",
        toolbarToggle("bi-text-wrap", "Wrap long lines", state.wrapSourceLines),
        toolbarButton("bi-clipboard", "Copy source", state.copySourceText()),
        // The docs link speaks the same icon-and-tooltip language as the rest of the app;
        // the per-language title is what the tooltip says.
        Tooltip(
          text = "Documentation",
          cls := "tooltip-bottom",
          a(
            cls    := "toolbar-btn",
            href   <-- currentInfo.map(_.documentationUrl),
            target := "_blank",
            title  <-- currentInfo.map(_.documentationTitle),
            i(cls := "bi-question-circle")
          )
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

private def toolbarButton(icon: String, tip: String, action: => Unit) =
  Tooltip(
    text = tip,
    cls := "tooltip-bottom",
    button(
      cls      := "toolbar-btn",
      typ      := "button",
      aria.label := tip,
      i(cls := icon),
      onClick --> action
    )
  )

private def toolbarToggle(icon: String, tip: String, flag: Var[Boolean]) =
  Tooltip(
    text = tip,
    cls := "tooltip-bottom",
    button(
      cls      := "toolbar-btn",
      typ      := "button",
      aria.label := tip,
      cls("active") <-- flag.signal,
      i(cls := icon),
      onClick --> flag.update(!_)
    )
  )
