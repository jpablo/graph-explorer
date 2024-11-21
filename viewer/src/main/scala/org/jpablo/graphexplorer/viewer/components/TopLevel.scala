package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import com.raquo.laminar.nodes.ReactiveHtmlElement
import io.laminext.syntax.core.*
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.components.attributes.SelectionAttributes
import org.jpablo.graphexplorer.viewer.components.leftPanel.LeftPanel
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.*
import org.jpablo.graphexplorer.viewer.widgets.Icons.*
import org.scalajs.dom
import org.scalajs.dom.HTMLDivElement

def TopLevel(state: ViewerState, router: Router): ReactiveHtmlElement[HTMLDivElement] =
  val fitDiagram = EventBus[Unit]()
  div(
    idAttr := "top-level",
    DiagramElementsButton(state),
    LeftPanel(state).render(),
    CanvasContainer(state, fitDiagram.events),
    Toolbar(state, fitDiagram, router),
//    SelectionSidebar(state),
    SelectionAttributes(state)
  )

def DiagramElementsButton(state: ViewerState) =
  val inputId = s"toggle-diagram-elements"
  div(
    idAttr := "diagram-elements-button",
    Tooltip(
      text = "Diagram elements",
      cls := "flex-none tooltip-right",
      input(idAttr := inputId, tpe := "checkbox", cls := "drawer-toggle"),
      label(
        forId := inputId,
        cls("btn-active") <-- state.leftPanelVisible,
        onClick --> state.leftPanelVisible.toggle()
      ).asBtn.tiny.outline.layoutSidebarIcon
    )
  )
