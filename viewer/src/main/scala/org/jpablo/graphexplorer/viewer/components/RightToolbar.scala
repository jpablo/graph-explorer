package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.*

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
