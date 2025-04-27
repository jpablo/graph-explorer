package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.state.RightPanelSection.*
import org.jpablo.graphexplorer.viewer.state.{RightPanelSection, ViewerState}
import org.jpablo.graphexplorer.viewer.widgets.*

def RightToolbar(state: ViewerState) =
  div(
    idAttr := "right-toolbar",
    List(
      diagramAttributes -> ("bi-sliders", "Diagram"),
      elements -> ("bi-list-ul", "Elements"),
      sources -> ("bi-code-square", "Source")
    ).map:
      case (section, (icon, text)) =>
        Tooltip(
          text = text,
          cls := "tooltip-left",
          span(
            cls := "cursor-pointer p-1.5 hover:bg-base-300 rounded-lg",
            cls("bg-base-300") <-- state.isSectionActive(section),
            i(cls := s"bi $icon"),
            onClick --> state.rightPanelActiveSection.update: curr =>
              if curr == section then none else section
          )
        )
  )
