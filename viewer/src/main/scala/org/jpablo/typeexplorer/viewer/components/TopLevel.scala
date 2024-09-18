package org.jpablo.typeexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import io.laminext.syntax.core.*
import org.jpablo.typeexplorer.viewer.components.nodes.LeftPanel
import org.jpablo.typeexplorer.viewer.state.ViewerState
import org.scalajs.dom
import org.scalajs.dom.HTMLDivElement

def TopLevel(state: ViewerState): ReactiveHtmlElement[HTMLDivElement] =
  val fitDiagram = EventBus[Unit]()
  div(
    idAttr := "top-level",
    state.sideBarVisible.signal.childWhenTrue:
      LeftPanel(state),
    CanvasContainer(state, fitDiagram.events),
    Toolbar(state, fitDiagram),
    SelectionSidebar(state),
  )
