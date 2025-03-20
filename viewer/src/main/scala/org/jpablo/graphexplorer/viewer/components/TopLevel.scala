package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import io.laminext.syntax.core.*
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
    LeftPanel(state, router, commands),
    CanvasContainer(state, commands),
//    CommandsPanel(state, commands),
    Toolbar(state.project.name.signal, commands, state),
    RightPanel(state).render(),
    RightToolbar(state),
    HelpDialog(state.shortcutsModalOpen, commands)
  )

def RightToolbar(state: ViewerState) =
  val visibleTab = state.rightPanelTabIndex
  def isVisible(i: Int) = visibleTab.signal.map(_ == i)

  val buttons =
    List(
      ("bi-pencil", "Style"),
      ("bi-pencil-square", "Defaults"),
      ("bi-diagram-2", "Elements"),
      ("bi-code-square", "Source")
    ).zipWithIndex.map:
      case ((icon, text), idx) =>
        Tooltip(
          text = text,
          cls := "tooltip-left",
          Button(
            cls("btn-active") <-- isVisible(idx),
            cls("btn-ghost") <-- isVisible(idx).not,
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
