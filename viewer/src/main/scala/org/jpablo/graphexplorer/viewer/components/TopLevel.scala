package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import io.laminext.syntax.core.*
import org.jpablo.graphexplorer.viewer.components.nodes.LeftPanel
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.scalajs.dom
import org.scalajs.dom.HTMLDivElement

def TopLevel(state: ViewerState): ReactiveHtmlElement[HTMLDivElement] =
  val fitDiagram = EventBus[Unit]()
  div(
    idAttr := "top-level",
    state.leftPanelVisible.signal.childWhenTrue:
      LeftPanel(state),
    CanvasContainer(state, fitDiagram.events),
    Toolbar(state, fitDiagram),
    SelectionSidebar(state),
  )
