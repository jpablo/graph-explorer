package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.eventbus.EventBus
import com.raquo.airstream.state.Var

trait UIState:
  val rightPanelTabIndex   = Var(0)
  val helpDialogOpen       = Var(false)
  val leftPanelVisible     = Var(true)
  val canvasContainerFocus = EventBus[Boolean]()
  val aboutDialogOpen      = Var(false)
