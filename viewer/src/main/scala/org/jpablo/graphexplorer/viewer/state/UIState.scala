package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.state.Var

trait UIState:
  val rightPanelVisible = Var(true)
  val rightPanelTabIndex = Var(0)
  val helpDialogOpen = Var(false)
  val leftPanelVisible = Var(true)
  val canvasContainerFocus = Var(true)

