package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.state.RightPanelSection.*
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.*

/** The right panel's section toggles: Diagram, Elements, Source. Formerly a
  * vertical bar pinned to the right edge (#right-toolbar); now a boxed cluster
  * in the main toolbar's right zone — a fully collapsed workspace shows no
  * rail at all and the canvas owns the right edge.
  */
def PanelSectionToggles(state: ViewerState) =
  div(
    idAttr := "panel-toggles",
    cls    := "flex items-center gap-0.5 shrink-0 print:hidden",
    List(
      diagramAttributes -> ("bi-sliders", "Diagram", TooltipPos.bottom),
      elements          -> ("bi-list-ul", "Elements", TooltipPos.bottom),
      // The last toggle sits by the window edge; an end-aligned bubble stays on screen.
      sources -> ("bi-code-square", "Source", TooltipPos.bottomEnd)
    ).map:
      case (section, (icon, text, tipPos)) =>
        Tooltip(
          text = text,
          cls := tipPos,
          button(
            cls        := "gx-icon-btn",
            typ        := "button",
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
