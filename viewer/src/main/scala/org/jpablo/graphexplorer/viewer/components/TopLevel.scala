package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.components.leftPanel.LeftPanel
import org.jpablo.graphexplorer.viewer.components.rightPanel.RightPanel
import org.jpablo.graphexplorer.viewer.components.svgCanvas.CanvasContainer
import org.jpablo.graphexplorer.viewer.state.ViewerState

def TopLevel(
    state:    ViewerState,
    router:   Router,
    commands: Commands
): Div =
  div(
    idAttr := "top-level",
    Toolbar(state.displayTitle, commands, state),
    AttributesToolbar(state.displayTitle, commands, state),
    div(
      cls := "flex flex-1 overflow-y-auto relative",
      LeftPanel(state, router, commands),
      CanvasContainer(state, commands),
      ZoomToolbar(state, commands),
      RightPanel(state),
      HelpDialog(state.helpDialogOpen, commands),
      AboutDialog(state.aboutDialogOpen),
      InfoAlert(state.infoBus),
      EditLabelDialog(state),
      NewNodeLabelDialog(state),
      NewGroupLabelDialog(state),
      ErrorAlert(state.errorBus)
    )
  )
