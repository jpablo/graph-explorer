package org.jpablo.graphexplorer.viewer.components.rightPanel

import com.raquo.laminar.api.L.*
import io.laminext.syntax.core.*
import org.jpablo.graphexplorer.viewer.components.attributes.views.{DiagramAttributesView, ElementsView}
import org.jpablo.graphexplorer.viewer.state.RightPanelSection.{diagramAttributes, elements, sources}
import org.jpablo.graphexplorer.viewer.state.ViewerState

class RightPanel(state: ViewerState):
  private val activeSection =
    state.rightPanelActiveSection.signal

  private val activeSectionPair =
    activeSection.scanLeft(x0 => (x0, x0)) { case ((x, y), next) => (y, next) }

  private val useTransition =
    activeSectionPair.map: (curr, next) =>
      val open  = (curr.isVisible || (curr == diagramAttributes)) && ((next == elements) || next == sources)
      val close = next.isVisible && ((next == elements) || next == sources)
      open || close

  val isFloating = activeSection.map(_ == diagramAttributes)

  def render() =
    div(
      idAttr := "right-panel",
      cls <-- state.rightPanelActiveSection.signal.map(s => if s.isVisible then "visible" else "not-visible"),
      cls("floating card card-xs") <-- isFloating,
      cls("transition-all duration-200") <-- useTransition,
      div(
        idAttr := "right-panel-content",
        cls("card-body") <-- isFloating,
        List(
          diagramAttributes -> DiagramAttributesView(state),
          elements          -> ElementsView(state),
          sources           -> SourceTab(state)
        ).map: (section, child) =>
          child.amend(cls := "h-full max-h-full flex flex-col", cls("hidden") <-- state.isSectionActive(section).not)
      )
    )
