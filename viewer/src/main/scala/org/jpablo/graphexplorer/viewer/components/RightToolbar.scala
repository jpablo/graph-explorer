package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.state.RightPanelSection.*
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.*

def RightToolbar(state: ViewerState) =
  div(
    idAttr := "right-toolbar",
    List(
      diagramAttributes -> ("bi-sliders", "Diagram"),
      elements          -> ("bi-list-ul", "Elements"),
      sources           -> ("bi-code-square", "Source")
    ).map:
      case (section, (icon, text)) =>
        Tooltip(
          text = text,
          cls := "tooltip-left",
          button(
            // `p-1.5` used to sit here and never applied: `.gx-icon-btn`'s own `p-1` ties on
            // specificity and wins on order. The padding is the vocabulary's, not this bar's.
            cls        := "gx-icon-btn",
            typ        := "button",
            // Was a `span`: no accessible name, and unreachable by keyboard. Every other
            // control in this vocabulary is a button that says what it opens.
            aria.label := s"$text panel",
            cls("active") <-- state.isSectionActive(section),
            i(
              cls := icon,
              cls("text-error") <-- state.editorNotice.signal.map(_.exists(_.isError) && section == sources)
            ),
            onClick --> state.rightPanelActiveSection.update: curr =>
              if curr == section then none else section
          )
        )
  )
