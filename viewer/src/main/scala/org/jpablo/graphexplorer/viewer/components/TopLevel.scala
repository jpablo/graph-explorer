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
    Toolbar(state.project.name.signal, commands, state),
    AttributesToolbar(state.project.name.signal, commands, state),
    div(
      cls := "flex flex-1 overflow-y-auto",
      LeftPanel(state, router, commands),
      CanvasContainer(state, commands),
      ZoomToolbar(commands),
      RightPanel(state).render(),
      RightToolbar(state),
      HelpDialog(state.shortcutsModalOpen, commands)
    )
  )
