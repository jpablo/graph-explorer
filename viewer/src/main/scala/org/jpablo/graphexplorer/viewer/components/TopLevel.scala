package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.jpablo.graphexplorer.viewer.components.leftPanel.LeftPanel
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.scalajs.dom
import org.scalajs.dom.HTMLDivElement

def TopLevel(state: ViewerState): ReactiveHtmlElement[HTMLDivElement] =
  val fitDiagram = EventBus[Unit]()
  div(
    idAttr := "top-level",
    child(LeftPanel(state)) <-- state.leftPanelVisible,
    CanvasContainer(state, fitDiagram.events),
    Toolbar(state, fitDiagram),
    SelectionSidebar(state),
  )
