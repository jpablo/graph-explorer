package org.jpablo.graphexplorer.viewer.components

import com.raquo.airstream.core.Signal
import com.raquo.laminar.api.L.*
import io.laminext.syntax.core.*
import org.jpablo.graphexplorer.viewer.state.{ViewerState, VisibleNodes}
import org.scalajs.dom

def SelectionSidebar(state: ViewerState) =
  val visibleNodes: Signal[VisibleNodes] =
    state.visibleNodes.signal

  val selectionEmpty =
    state.diagramSelection.signal.map(_.isEmpty)
  div(
    cls    := "absolute right-0 top-2 z-10",
    idAttr := "selection-sidebar",
    selectionEmpty.childWhenFalse(
      ul(
        cls := "menu shadow bg-base-100 rounded-box m-2 p-0",
        li(
          h2(cls := "menu-title", span("selection")),
          ul(
            // ----- remove selection -----
            li(
              cls("disabled") <-- selectionEmpty,
              a(
                "Remove",
                disabled <-- selectionEmpty,
                state.visibleNodes.applyOnSelection((all, sel) => all -- sel)(onClick)
              )
            ),
            // ----- remove complement -----
            li(
              cls("disabled") <-- selectionEmpty,
              a(
                "Keep",
                disabled <-- selectionEmpty,
                state.visibleNodes.applyOnSelection((all, sel) => all.filter((k, _) => sel.contains(k)))(onClick)
              )
            ),
            // ----- copy as svg -----
            li(
              cls("disabled") <-- selectionEmpty,
              a(
                "Copy as SVG",
                disabled <-- selectionEmpty,
                onClick.compose(
                  _.sample(state.svgDotDiagram, state.diagramSelection.signal)
                ) --> { (svgDiagram, canvasSelection) =>
                  dom.window.navigator.clipboard
                    .writeText(svgDiagram.toSVGText(canvasSelection))
                }
              )
            ),
            // ----- augment selection with parents -----
            li(
              cls("disabled") <-- selectionEmpty,
              a(
                "Add successors",
                disabled <-- selectionEmpty,
                state.visibleNodes.addSelectionWith(onClick)((g, id) => g.unfoldSuccessors(Set(id)))
              )
            ),
            // ----- augment selection with direct successors -----
            li(
              cls("disabled") <-- selectionEmpty,
              a(
                "Add direct successors",
                disabled <-- selectionEmpty,
                state.visibleNodes.addSelectionWith(onClick)((g, id) => g.directSuccessors(Set(id)))
              )
            ),
            // ----- augment selection with children -----
            li(
              cls("disabled") <-- selectionEmpty,
              a(
                "Add predecessors",
                disabled <-- selectionEmpty,
                state.visibleNodes.addSelectionWith(onClick)((g, id) => g.unfoldPredecessors(Set(id)))
              )
            ),
            // ----- augment selection with direct predecessors -----
            li(
              cls("disabled") <-- selectionEmpty,
              a(
                "Add direct predecessors",
                disabled <-- selectionEmpty,
                state.visibleNodes.addSelectionWith(onClick)((g, id) => g.directPredecessors(Set(id)))
              )
            ),
//            // ----- add selection to set of hidden symbols -----
//            li(
//              cls("disabled") <-- selectionEmpty,
//              a(
//                "Hide",
//                disabled <-- selectionEmpty,
//                onClick -->
//                  state.project.update:
//                    _.modify(_.projectSettings.hiddenNodeIds)
//                      .using(_ ++ state.diagramSelection.now())
//              )
//            ),
            // ----- select parents -----
            li(
              cls("disabled") <-- selectionEmpty,
              a(
                "Select successors",
                disabled <-- selectionEmpty,
                onClick.compose(
                  _.sample(
                    state.fullGraph,
                    state.svgDotDiagram,
                    visibleNodes
                  )
                ) -->
                  state.diagramSelection.selectSuccessors.tupled
              )
            ),
            // ----- select direct parents -----
            li(
              cls("disabled") <-- selectionEmpty,
              a(
                "Select direct successors",
                disabled <-- selectionEmpty,
                onClick.compose(
                  _.sample(
                    state.fullGraph,
                    state.svgDotDiagram,
                    visibleNodes
                  )
                ) -->
                  state.diagramSelection.selectDirectSuccessors.tupled
              )
            ),
            // ----- select predecessors -----
            li(
              cls("disabled") <-- selectionEmpty,
              a(
                "Select predecessors",
                onClick.compose(
                  _.sample(
                    state.fullGraph,
                    state.svgDotDiagram,
                    visibleNodes
                  )
                ) -->
                  state.diagramSelection.selectPredecessors.tupled
              )
            ),
            // ----- select direct predecessors -----
            li(
              cls("disabled") <-- selectionEmpty,
              a(
                "Select direct predecessors",
                onClick.compose(
                  _.sample(
                    state.fullGraph,
                    state.svgDotDiagram,
                    visibleNodes
                  )
                ) -->
                  state.diagramSelection.selectDirectPredecessors.tupled
              )
            )
          )
        )
      )
    )
  )
