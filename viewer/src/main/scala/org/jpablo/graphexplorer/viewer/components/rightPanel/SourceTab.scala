package org.jpablo.graphexplorer.viewer.components.rightPanel

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.codeMirror.CodeMirror
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.{
  AlertBox,
  AlertTone,
  Button,
  IconButton,
  IconToggle,
  SelectBox,
  SelectVariant,
  Tooltip,
  TooltipPos,
  primary,
  tiny
}

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
        // Auto leads: it is the option that needs no knowledge of the languages
        // to pick correctly. Its label carries what it RESOLVED to, so choosing
        // it does not cost you the ability to see which backend is running.
        option(value := state.formatOption.auto, text <-- state.formatOption.autoLabel),
        state.availableFormats.map: (format, info) =>
          option(
            value := format.toString,
            info.selectorLabel
          ),
        controlled(
          value <-- state.formatOption.selected,
          onChange.mapToValue --> state.formatOption.select
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
        // Paste's home is next to Copy, on the panel that owns the text: the
        // format selector to its left is what this button overwrites, so the
        // auto-detection is visible where it happens.
        IconButton("bi-clipboard-plus", "Paste diagram (replaces the source, DOT or Mermaid)", TooltipPos.bottomEnd)(
          state.pasteDiagram()
        ),
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
        AlertBox(
          tone,
          cls := "m-1 mt-2 p-1 rounded-box flex-none text-sm",
          // ONE child, deliberately: daisyUI's `.alert` lays its children out in
          // a ROW, which squeezed the message into a column of single words
          // beside the buttons and pushed the last one out of the panel.
          div(
            cls := "flex flex-col gap-1.5 w-full min-w-0",
            span(notice.message),
            // The one failure the notice can FIX rather than merely describe.
            // Both ways out are offered: this once, or from now on.
            notice.suggestedFormat.map: actual =>
              div(
                cls := "flex flex-wrap gap-1",
                Button(
                  s"Switch to ${state.formatInfo(actual).selectorLabel}",
                  onClick --> (_ => state.setDiagramFormat(actual))
                ).primary.tiny,
                Button(
                  "Always detect",
                  onClick --> (_ => state.autoDetectFormat.set(true))
                ).tiny
              )
          )
        )
  )
