package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.components.leftPanel.LeftPanel
import org.jpablo.graphexplorer.viewer.components.rightPanel.RightPanel
import org.jpablo.graphexplorer.viewer.components.svgCanvas.CanvasContainer
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.*

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

def RightToolbar(state: ViewerState) =
  def isVisible(i: Int) = state.rightPanelTabIndex.signal.map(_ == i)

  div(
    idAttr := "right-toolbar",
    List(
      ("bi-pencil", "Diagram"),
      ("bi-list-ul", "Elements"),
      ("bi-code-square", "Source")
    ).zipWithIndex.map:
      case ((icon, text), idx) =>
        Tooltip(
          text = text,
          cls := "tooltip-left",
          span(
            cls := "cursor-pointer p-1.5 hover:bg-base-300 rounded-lg",
            cls("bg-base-300") <-- isVisible(idx),
            i(cls := s"bi $icon"),
            onClick --> state.rightPanelTabIndex.update: j =>
              if idx == j then -1 else idx
          )
        ),
    state.rightPanelTabIndex.signal --> { idx =>
      if idx == -1 then state.rightPanelVisible.set(false)
      else state.rightPanelVisible.set(true)
    }
  )
