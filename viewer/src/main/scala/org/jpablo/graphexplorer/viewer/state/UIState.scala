package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.eventbus.EventBus
import com.raquo.airstream.state.Var

enum RightPanelSection(idx: Int) derives CanEqual:
  case none              extends RightPanelSection(-1)
  case diagramAttributes extends RightPanelSection(0)
  case elements          extends RightPanelSection(1)
  case sources           extends RightPanelSection(2)

  def isVisible   = this != none

trait UIState:
  val rightPanelActiveSection = Var(RightPanelSection.none)
  val helpDialogOpen          = Var(false)
  val leftPanelVisible        = Var(true)
  val canvasContainerFocus    = EventBus[Boolean]()
  val aboutDialogOpen         = Var(false)

  extension (section: RightPanelSection)
    def isSectionActive: Signal[Boolean] =
      rightPanelActiveSection.signal.map(_ == section)
