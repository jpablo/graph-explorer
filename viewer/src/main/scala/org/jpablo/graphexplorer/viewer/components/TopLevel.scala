package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import com.raquo.laminar.nodes.ReactiveHtmlElement
import io.laminext.syntax.core.*
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.components.rightPanel.RightPanel
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.*
import org.jpablo.graphexplorer.viewer.widgets.Icons.*
import org.scalajs.dom
import org.scalajs.dom.HTMLDivElement
import org.jpablo.graphexplorer.viewer.components.selection.SelectionSidebar

def TopLevel(state: ViewerState, router: Router): ReactiveHtmlElement[HTMLDivElement] =
  val fitDiagram = EventBus[Unit]()
  div(
    idAttr := "top-level",
    DiagramElementsButton(state),
    CanvasContainer(state, fitDiagram.events),
    Toolbar(state, fitDiagram, router),
    SelectionSidebar(state),
    RightPanel(state).render(),
    HelpDialog(state.shortcutsModalOpen)
  )

def DiagramElementsButton(state: ViewerState) =
  val inputId = s"toggle-diagram-elements"
  div(
    idAttr := "diagram-elements-button",
    Tooltip(
      text = "Style",
      cls := "flex-none tooltip-right",
      input(idAttr := inputId, tpe := "checkbox", cls := "drawer-toggle"),
      label(
        forId := inputId,
        cls("btn-active") <-- state.rightPanelVisible,
        onClick --> state.rightPanelVisible.toggle()
      ).asBtn.tiny.outline.layoutSidebarIcon
    )
  )
