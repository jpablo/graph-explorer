package org.jpablo.typeexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import io.laminext.syntax.core.*
import org.jpablo.typeexplorer.viewer.components.nodes.LeftPanel
import org.jpablo.typeexplorer.viewer.state.ViewerState
import org.scalajs.dom
import org.scalajs.dom.HTMLDivElement

def TopLevel(state: ViewerState): ReactiveHtmlElement[HTMLDivElement] =
  val zoomValue = Var(1.0)
  val fitDiagram = EventBus[Unit]()
  div(
    idAttr := "top-level",
    state.sideBarVisible.signal.childWhenTrue:
      LeftPanel(state),
    CanvasContainer(state, zoomValue, fitDiagram.events),
    Toolbar(state, zoomValue, fitDiagram),
    SelectionSidebar(state),
  )
