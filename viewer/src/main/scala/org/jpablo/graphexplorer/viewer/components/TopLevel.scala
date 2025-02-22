package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.components.rightPanel.RightPanel
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.scalajs.dom
import org.scalajs.dom.HTMLDivElement
import org.jpablo.graphexplorer.viewer.components.selection.SelectionSidebar
import org.jpablo.graphexplorer.viewer.components.leftPanel.LeftPanel

def TopLevel(state: ViewerState, router: Router): ReactiveHtmlElement[HTMLDivElement] =
  val fitDiagram = EventBus[Unit]()
  div(
    idAttr := "top-level",
    LeftPanel(state, router),
    CanvasContainer(state, fitDiagram.events),
    Toolbar(state, fitDiagram, router),
    SelectionSidebar(state),
    RightPanel(state).render(),
    HelpDialog(state.shortcutsModalOpen)
  )
