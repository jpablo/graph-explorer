package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.components.rightPanel.RightPanel
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.scalajs.dom
import org.scalajs.dom.HTMLDivElement
import org.jpablo.graphexplorer.viewer.components.leftPanel.CommandsPanel
import org.jpablo.graphexplorer.viewer.components.leftPanel.LeftPanel
import org.jpablo.graphexplorer.viewer.components.svgCanvas.CanvasContainer

def TopLevel(
    state:         ViewerState,
    router:        Router,
    commands:      Commands,
): ReactiveHtmlElement[HTMLDivElement] =
  div(
    idAttr := "top-level",
    styleAttr <-- state.leftPanelVisible.signal.map(visible =>
      if visible then "--selection-sidebar-left: 16.5rem;"
      else "--selection-sidebar-left: 2.75rem;"
    ),
    LeftPanel(state, router),
    CanvasContainer(state, commands),
    CommandsPanel(state, commands),
    Toolbar(state.project.name.signal, commands, state),
    RightPanel(state).render(),
    HelpDialog(state.shortcutsModalOpen, commands)
  )
