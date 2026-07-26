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
          span(
            cls := "gx-icon-btn p-1.5",
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
