package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import io.laminext.syntax.core.*
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.scalajs.dom

def SelectionSidebar(state: ViewerState) =
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
        // ----- remove selection -----
        li(
          disableClassIfEmpty,
          a("Remove", disableAttrIfEmpty, state.hideSelectedNodes(onClick))
        ),
        // ----- remove complement -----
        li(
          disableClassIfEmpty,
          a("Remove others", disableAttrIfEmpty, state.hideNonSelectedNodes(onClick))
        ),
        // ----- copy as svg -----
        li(
          disableClassIfEmpty,
          a(
            "Copy as SVG",
            disableAttrIfEmpty,
            onClick.compose(
              _.sample(state.svgDotDiagram, state.diagramSelection.signal)
            ) --> { (svgDiagram, canvasSelection) =>
              dom.window.navigator.clipboard.writeText(svgDiagram.toSVGText(canvasSelection))
            }
          )
        ),

        li(cls := "menu-title", "successors", hr()),
        // ----- augment selection with parents -----
        li(
          disableClassIfEmpty,
          a("Add all successors", disableAttrIfEmpty, state.showAllSuccessors(onClick))
        ),
        // ----- augment selection with direct successors -----
        li(
          disableClassIfEmpty,
          a("Add direct successors", disableAttrIfEmpty, state.showDirectSuccessors(onClick))
        ),
        // ----- select parents -----
        li(
          disableClassIfEmpty,
          a(
            "Select all successors",
            disableAttrIfEmpty,
            onClick.compose(_.sample(state.fullGraph, state.svgDotDiagram, state.hiddenNodesS)) -->
              state.diagramSelection.selectSuccessors.tupled
          )
        ),
        // ----- select direct parents -----
        li(
          disableClassIfEmpty,
          a(
            "Select direct successors",
            disableAttrIfEmpty,
            onClick.compose(_.sample(state.fullGraph, state.svgDotDiagram, state.hiddenNodesS)) -->
              state.diagramSelection.selectDirectSuccessors.tupled
          )
        ),

        li(cls := "menu-title", "predecessors", hr()),

        // ----- augment selection with children -----
        li(
          disableClassIfEmpty,
          a("Add all predecessors", disableAttrIfEmpty, state.showAllPredecessors(onClick))
        ),
        // ----- augment selection with direct predecessors -----
        li(
          disableClassIfEmpty,
          a("Add direct predecessors", disableAttrIfEmpty, state.showDirectPredecessors(onClick))
        ),
        // ----- select predecessors -----
        li(
          disableClassIfEmpty,
          a(
            "Select all predecessors",
            onClick.compose(_.sample(state.fullGraph, state.svgDotDiagram, state.hiddenNodesS)) -->
              state.diagramSelection.selectPredecessors.tupled
          )
        ),
        // ----- select direct predecessors -----
        li(
          disableClassIfEmpty,
          a(
            "Select direct predecessors",
            onClick.compose(_.sample(state.fullGraph, state.svgDotDiagram, state.hiddenNodesS)) -->
              state.diagramSelection.selectDirectPredecessors.tupled
          )
        )
      )
    )
  )
