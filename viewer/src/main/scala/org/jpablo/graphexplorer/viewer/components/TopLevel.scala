package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.components.attributes.views.miniViews.MiniStyleView
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
    cls    := "flex flex-col",
    Toolbar(state.project.name.signal, commands, state),
    div(
      cls := "flex h-full",
      LeftPanel(state, router, commands),
      child(SelectionPanel(state)) <-- state.selection.signal.map(_.nonEmpty),
      CanvasContainer(state, commands),
      ZoomToolbar(commands),
      RightPanel(state).render(),
      RightToolbar(state),
      HelpDialog(state.shortcutsModalOpen, commands)
    )
  )

def SelectionPanel(state: ViewerState) =
  div(
    idAttr := "selection-panel",
    cls("left-panel-visible") <-- state.leftPanelVisible,
    div(
      cls := "card card-xs",
      div(
        cls := "card-body",
        MiniStyleView(state)
      )
    )
  )

def RightToolbar(state: ViewerState) =
  val visibleTab        = state.rightPanelTabIndex
  def isVisible(i: Int) = visibleTab.signal.map(_ == i)

  val buttons =
    List(
      ("bi-pencil", "Diagram"),
      ("bi-pencil-square", "Default Style"),
      ("bi-list-ul", "Elements"),
      ("bi-code-square", "Source")
    ).zipWithIndex.map:
      case ((icon, text), idx) =>
        Tooltip(
          text = text,
          cls := "tooltip-left",
          span(
            cls := "cursor-pointer p-1.5 hover:bg-base-300 rounded-lg",
            cls("bg-base-300 hover:bg-base-300 border-1 border-base-300") <-- isVisible(idx),
            i(cls := s"bi $icon"),
            onClick --> visibleTab.update: j =>
              if idx == j then -1 else idx
          )
        )
  div(
    idAttr := "right-toolbar",
    buttons,
    visibleTab.signal --> { idx =>
      if idx == -1 then state.rightPanelVisible.set(false)
      else state.rightPanelVisible.set(true)
    }
  )
