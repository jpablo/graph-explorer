package org.jpablo.graphexplorer.viewer.components.rightPanel

import com.raquo.laminar.api.L.*
import io.laminext.syntax.core.*
import org.jpablo.graphexplorer.viewer.components.attributes.views.{DiagramAttributesView, ElementsView}
import org.jpablo.graphexplorer.viewer.state.ViewerState

class RightPanel(state: ViewerState):
  private val tabIndex =
    state.rightPanelTabIndex.signal

  private val tabIndexPair =
    tabIndex.scanLeft(x => (x, x)):
      case ((x, y), next) =>
        (y, next)

  private val useTransition =
    tabIndexPair.map: (curr, next) =>
      val open  = (curr == -1 || curr == 0) && (next == 1 || next == 2)
      val close = (next == -1) && (curr == 1 || curr == 2)
      open || close

  private def isVisible(i: Int) = tabIndex.map(_ == i)

  val isFloating = tabIndex.map(_ == 0)

  def render() =
    div(
      idAttr := "right-panel",
      cls <-- state.rightPanelTabIndex.signal.map(i => if i >= 0 then "visible" else "not-visible"),
      cls("floating card card-xs") <-- isFloating,
      cls("transition-all duration-200") <-- useTransition,
      div(
        idAttr := "right-panel-content",
        cls("card-body") <-- isFloating,
        List(
          DiagramAttributesView(state),
          ElementsView(state),
          SourceTab(state)
        ).zipWithIndex.map: (child, idx) =>
          child.amend(cls := "h-full max-h-full flex flex-col", cls("hidden") <-- !isVisible(idx))
      )
    )
