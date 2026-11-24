package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.state.RightPanelSection.*
import org.jpablo.graphexplorer.viewer.state.{RightPanelSection, ViewerState}
import org.jpablo.graphexplorer.viewer.widgets.*

/** The right panel's section toggles of one view. */
def PanelSectionToggles(state: ViewerState): Div =
  PanelSectionToggles(Val(state))

/** The right panel's section toggles: Diagram, Elements, Source. Formerly a
  * vertical bar pinned to the right edge (#right-toolbar); now a boxed cluster
  * in the main toolbar's right zone — a fully collapsed workspace shows no
  * rail at all and the canvas owns the right edge.
  *
  * The toolbar of the diagram workspace mounts the toggles one time, and gives
  * them the views one after the other in `views`. The values come from the
  * current view through one `flatMapSwitch`. A click reads the view at the time
  * of the click.
  */
def PanelSectionToggles(views: Signal[ViewerState]): Div =
  // The active section, and true when the source has an error.
  val panel: Signal[(RightPanelSection, Boolean)] =
    views.flatMapSwitch: state =>
      state.rightPanelActiveSection.signal.combineWith(state.editorNotice.signal.map(_.exists(_.isError)))
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
            cls("active") <-- panel.map(_._1 == section),
            i(
              cls := icon,
              cls("text-error") <-- panel.map(_._2 && section == sources)
            ),
            onClick(_.sample(views)) --> (state =>
              state.rightPanelActiveSection.update: curr =>
                if curr == section then none else section
            )
          )
        )
  )
