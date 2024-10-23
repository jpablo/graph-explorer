package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import io.laminext.syntax.core.*
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.scalajs.dom
import org.scalajs.dom.window

def SelectionSidebar(state: ViewerState) =
  import state.eventHandlers.*

  val selectionEmpty =
    state.diagramSelection.signal.map(_.isEmpty)
  val disableClassIfEmpty = cls("disabled") <-- selectionEmpty
  val disableAttrIfEmpty = disabled <-- selectionEmpty
  div(
    cls    := "absolute right-0 top-2 z-10",
    idAttr := "selection-sidebar",
    selectionEmpty.childWhenFalse(
      ul(
        cls := "menu menu-sm shadow bg-base-100 rounded-box m-2 p-0",
        li(cls := "menu-title", h1("selection"), hr()),
        li(disableClassIfEmpty, a("Hide", disableAttrIfEmpty, onClick.hideSelectedNodes)),
        li(disableClassIfEmpty, a("Hide others", disableAttrIfEmpty, onClick.hideNonSelectedNodes)),
        // ----- copy as svg -----
        li(
          disableClassIfEmpty,
          a("Copy as SVG", disableAttrIfEmpty, onClick.copyAsFullDiagramSVG(window.navigator.clipboard.writeText))
        ),
        li(cls := "menu-title", "successors", hr()),
        li(disableClassIfEmpty, a("Show all successors", disableAttrIfEmpty, onClick.showAllSuccessors)),
        li(disableClassIfEmpty, a("Show direct successors", disableAttrIfEmpty, onClick.showDirectSuccessors)),
        li(disableClassIfEmpty, a("Select all successors", disableAttrIfEmpty, onClick.selectSuccessors)),
        li(disableClassIfEmpty, a("Select direct successors", disableAttrIfEmpty, onClick.selectDirectSuccessors)),
        li(cls := "menu-title", "predecessors", hr()),
        li(disableClassIfEmpty, a("Show all predecessors", disableAttrIfEmpty, onClick.showAllPredecessors)),
        li(disableClassIfEmpty, a("Show direct predecessors", disableAttrIfEmpty, onClick.showDirectPredecessors)),
        li(disableClassIfEmpty, a("Select all predecessors", onClick.selectPredecessors)),
        li(disableClassIfEmpty, a("Select direct predecessors", onClick.selectDirectPredecessors))
      )
    )
  )
