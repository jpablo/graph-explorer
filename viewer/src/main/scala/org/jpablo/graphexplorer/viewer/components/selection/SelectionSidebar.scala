package org.jpablo.graphexplorer.viewer.components.selection

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.scalajs.dom
import org.scalajs.dom.window

def SelectionSidebar(state: ViewerState) =
  import state.eventHandlers.*

  val selectionEmpty =
    state.diagramSelection.signal.map(_.isEmpty)
  div(
    idAttr := "selection-sidebar",
    child(
      ul(
        cls := "menu menu-sm bg-base-100 rounded-box",
        li(cls := "menu-title", h1("selection"), hr()),
        li(a(cls := "flex justify-between", span("Hide"), span(cls := "text-base-content/50", "(h)"), onClick.hideSelectedNodes)),
        li(a(cls := "flex justify-between", span("Hide others"), span(cls := "text-base-content/50", "(Shift+h)"), onClick.hideNonSelectedNodes)),
        li(a(cls := "flex justify-between", span("Add node"), span(cls := "text-base-content/50", "(n)"), onClick.addNode)),
        li(a(cls := "flex justify-between", span("Delete"), span(cls := "text-base-content/50", "(Del)"), onClick.deleteSelectedNodes)),
        li(a(cls := "flex justify-between", span("Group"), span(cls := "text-base-content/50", "(g)"), onClick.groupSelectedNodes)),
        li(a(cls := "flex justify-between", span("Clear selection"), span(cls := "text-base-content/50", "(Esc)"), onClick.clearSelection)),
        // ----- copy as svg -----
        li(
          a("Copy as SVG", onClick.copySelectionAsSVG(window.navigator.clipboard.writeText))
        ),
        li(cls := "menu-title", "successors", hr()),
        li(a("Show all successors", onClick.showAllSuccessors)),
        li(a("Show direct successors", onClick.showDirectSuccessors)),
        li(a("Select all successors", onClick.selectSuccessors)),
        li(a("Select direct successors", onClick.selectDirectSuccessors)),
        li(cls := "menu-title", "predecessors", hr()),
        li(a("Show all predecessors", onClick.showAllPredecessors)),
        li(a("Show direct predecessors", onClick.showDirectPredecessors)),
        li(a("Select all predecessors", onClick.selectPredecessors)),
        li(a("Select direct predecessors", onClick.selectDirectPredecessors))
      )
    ) <-- selectionEmpty.not
  )
