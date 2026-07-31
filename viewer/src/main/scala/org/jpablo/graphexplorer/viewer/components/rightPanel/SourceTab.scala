package org.jpablo.graphexplorer.viewer.components.rightPanel

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.codeMirror.CodeMirror
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.{AlertBox, AlertTone, IconButton, IconToggle, SelectBox, SelectVariant, Tooltip, TooltipPos}

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
      SelectBox(
        SelectVariant.ghost,
        SelectVariant.xs,
        cls := "source-format",
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
        // END-aligned bubbles: these icons sit against the panel's right edge, and a
        // CENTRED daisyUI tooltip is a pseudo-element wider than its 26px trigger — it
        // spilled ~43px past the edge, and since no ancestor clips, the app's main flex
        // row (overflow-y-auto, so the x axis computes to `auto` too) became
        // horizontally scrollable: a scrollbar under the whole window and empty space
        // to scroll into. Same trap IconButtonTitled documents for the breadcrumbs.
        IconToggle("bi-text-wrap", "Wrap long lines", state.wrapSourceLines, TooltipPos.bottomEnd),
        IconButton("bi-clipboard", "Copy source", TooltipPos.bottomEnd)(state.copySourceText()),
        // The docs link speaks the same icon-and-tooltip language as the rest of the app;
        // the per-language title is what the tooltip says.
        Tooltip(
          text = "Documentation",
          cls := TooltipPos.bottomEnd,
          a(
            cls    := "gx-icon-btn",
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
        val tone = if notice.isError then AlertTone.Error else AlertTone.Info
        AlertBox(tone, cls := "m-1 mt-2 p-1 rounded-box flex-none text-sm", span(notice.message))
  )
