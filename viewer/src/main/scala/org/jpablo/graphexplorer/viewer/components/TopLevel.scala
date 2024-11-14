package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.components.attributes.DiagramAttributesView
import org.jpablo.graphexplorer.viewer.components.leftPanel.LeftPanel
import org.jpablo.graphexplorer.viewer.components.selection.SelectionSidebar
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.scalajs.dom
import org.scalajs.dom.HTMLDivElement

def TopLevel(state: ViewerState, router: Router): ReactiveHtmlElement[HTMLDivElement] =
  val fitDiagram = EventBus[Unit]()
  div(
    idAttr := "top-level",
    child(DiagramAttributesView(state)) <-- state.diagramAttributesVisible,
    LeftPanel(state).amend(cls("hidden") <-- state.leftPanelVisible.signal.not),
    CanvasContainer(state, fitDiagram.events),
    Toolbar(state, fitDiagram, router),
    SelectionSidebar(state),
  )
