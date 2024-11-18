package org.jpablo.graphexplorer.viewer.components.selection

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.scalajs.dom
import org.scalajs.dom.window

def SelectionSidebar(state: ViewerState) =
  import state.eventHandlers.*

  val selectionEmpty =
    state.diagramSelection.signal.map(_.isEmpty)
  val disableIfEmpty = cls("disabled") <-- selectionEmpty
  val disableAttrIfEmpty = disabled <-- selectionEmpty
  div(
    idAttr := "selection-sidebar",
    child(
      ul(
        cls := "menu menu-sm bg-base-100 rounded-box",
        li(cls := "menu-title", h1("selection"), hr()),
        li(disableIfEmpty, a("Hide", disableAttrIfEmpty, onClick.hideSelectedNodes)),
        li(disableIfEmpty, a("Hide others", disableAttrIfEmpty, onClick.hideNonSelectedNodes)),
        // ----- copy as svg -----
        li(
          disableIfEmpty,
          a("Copy as SVG", disableAttrIfEmpty, onClick.copySelectionAsSVG(window.navigator.clipboard.writeText))
        ),
        li(cls := "menu-title", "successors", hr()),
        li(disableIfEmpty, a("Show all successors", disableAttrIfEmpty, onClick.showAllSuccessors)),
        li(disableIfEmpty, a("Show direct successors", disableAttrIfEmpty, onClick.showDirectSuccessors)),
        li(disableIfEmpty, a("Select all successors", disableAttrIfEmpty, onClick.selectSuccessors)),
        li(disableIfEmpty, a("Select direct successors", disableAttrIfEmpty, onClick.selectDirectSuccessors)),
        li(cls := "menu-title", "predecessors", hr()),
        li(disableIfEmpty, a("Show all predecessors", disableAttrIfEmpty, onClick.showAllPredecessors)),
        li(disableIfEmpty, a("Show direct predecessors", disableAttrIfEmpty, onClick.showDirectPredecessors)),
        li(disableIfEmpty, a("Select all predecessors", onClick.selectPredecessors)),
        li(disableIfEmpty, a("Select direct predecessors", onClick.selectDirectPredecessors))
      )
    ) <-- selectionEmpty.not
  )
