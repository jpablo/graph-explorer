package org.jpablo.graphexplorer.viewer.components.selection

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.scalajs.dom.window

def SelectionSidebar(state: ViewerState) =
  import state.eventHandlers.*

  val selectionEmpty =
    state.diagramSelection.signal.map(_.isEmpty)
  div(
    idAttr := "selection-sidebar",
    child(
      ul(
        cls := "menu menu-sm rounded-box bg-transparent",
        li(cls := "menu-title", h1("selection"), hr()),
        li(a(
          cls := "flex justify-between",
          span("Hide"),
          kbd(cls := "kbd kbd-sm opacity-60", "h"),
          onMouseDown.hideSelectedNodes
        )),
        li(a(
          cls := "flex justify-between",
          span("Hide others"),
          kbd(cls := "kbd kbd-sm opacity-60", "Shift+h"),
          onMouseDown.hideNonSelectedNodes
        )),
        li(a(
          cls := "flex justify-between",
          span("Add node"),
          kbd(cls := "kbd kbd-sm opacity-60", "n"),
          onMouseDown --> state.addNode()
        )),
        li(a(
          cls := "flex justify-between",
          span("Delete"),
          kbd(cls := "kbd kbd-sm opacity-60", "Del"),
          onMouseDown.deleteSelectedNodes
        )),
        li(a(
          cls := "flex justify-between",
          span("Group"),
          kbd(cls := "kbd kbd-sm opacity-60", "g"),
          onMouseDown.groupSelectedNodes
        )),
        li(a(
          cls := "flex justify-between",
          span("Clear selection"),
          kbd(cls := "kbd kbd-sm opacity-60", "Esc"),
          onMouseDown.clearSelection
        )),
        // ----- copy as svg -----
        li(a("Copy as SVG", onMouseDown.copySelectionAsSVG(window.navigator.clipboard.writeText))),
        li(cls := "menu-title", "successors", hr()),
        li(a("Show all successors", onMouseDown.showAllSuccessors)),
        li(a("Show direct successors", onMouseDown.showDirectSuccessors)),
        li(a("Select all successors", onMouseDown.selectSuccessors)),
        li(a("Select direct successors", onMouseDown.selectDirectSuccessors)),
        li(cls := "menu-title", "predecessors", hr()),
        li(a("Show all predecessors", onMouseDown.showAllPredecessors)),
        li(a("Show direct predecessors", onMouseDown.showDirectPredecessors)),
        li(a("Select all predecessors", onMouseDown.selectPredecessors)),
        li(a("Select direct predecessors", onMouseDown.selectDirectPredecessors))
      )
    ) <-- selectionEmpty.not
  )
