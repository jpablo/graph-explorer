package org.jpablo.typeexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.jpablo.typeexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.typeexplorer.viewer.graph.ViewerGraph
import org.jpablo.typeexplorer.viewer.widgets.Icons.*
import org.jpablo.typeexplorer.viewer.widgets.*
import org.scalajs.dom
import org.scalajs.dom.{HTMLDivElement, HTMLElement}
import org.jpablo.typeexplorer.viewer.backends.graphviz.Graphviz.toDot
import org.jpablo.typeexplorer.viewer.state.{DiagramOptions, ViewerState}

def Toolbar(
    fullGraph:       Signal[ViewerGraph],
    tabState:        ViewerState,
    zoomValue:       Var[Double],
    fitDiagram:      EventBus[Unit],
    replaceTextOpen: Var[Boolean]
) =
  val drawerId = s"drawer-id"
  div(
    cls := "shadow bg-base-100 rounded-box flex items-center gap-4 p-0.5 absolute top-1 left-2/4 -translate-x-2/4 z-10",
    // -------- package selector --------
    Join(
      Tooltip(
        text = "Select visible nodes",
        input(idAttr := drawerId, tpe := "checkbox", cls := "drawer-toggle"),
        label(
          forId := drawerId,
          cls   := "btn btn-ghost btn-sm bi bi-boxes"
//          onClick --> appConfigDialogOpenV.set(true)
        )
      ).amend(cls := "flex-none")
    ),
    // -------- fields and signatures --------
//    Join(
//      LabeledCheckbox(
//        id        = "fields-checkbox-1",
//        labelStr  = "fields",
//        isChecked = tabState.diagramOptionsV.signal.map(_.showFields),
//        clickHandler = tabState.diagramOptionsV
//          .updater(_.modify(_.showFields).setTo(_)),
//        toggle = true
//      )
//    ),
    // -------- actions toolbar --------
    Join(
      Button(Tooltip("CSV", "contents"), onClick --> replaceTextOpen.set(true)).tiny,
      Button(
        "add all",
        onClick.compose(_.sample(tabState.allNodeIds).map(_.toSeq)) --> (tabState.activeSymbols.extend(_))
      ).tiny,
      Button("remove all", onClick --> tabState.activeSymbols.clear()).tiny,
      div(
        cls := "dropdown dropdown-hover",
        label(
          tabIndex := 0,
          cls      := "btn btn-xs join-item whitespace-nowrap",
          "Copy as"
        ),
        ul(
          tabIndex := 0,
          cls      := "dropdown-content z-[1] menu p-2 shadow bg-base-100 rounded-box w-52",
          li(
            a(
              "svg",
              onClick.compose(_.sample(tabState.svgDiagram)) --> { diagram =>
                dom.window.navigator.clipboard.writeText(diagram.toSVGText)
              }
            )
          ),
          li(
            a("dot", onDotClicked(fullGraph, tabState))
          )
        )
      )
    ),
    // ----------
    Join(
      Button(span.dashIcon, onClick --> zoomValue.update(_ * 0.9)).tiny,
      Button("fit", onClick --> fitDiagram.emit(())).tiny,
      Button(span.plusIcon, onClick --> zoomValue.update(_ * 1.1)).tiny
    ),
    Join(
      input(
        tpe      := "range",
        cls      := "range range-xs pr-3",
        minAttr  := 0.1.toString,
        maxAttr  := 5.0.toString,
        stepAttr := "0.05",
        controlled(
          value <-- zoomValue.signal.map(_.toString),
          onInput.mapToValue.map(_.toDouble) --> zoomValue
        )
      )
    )
  )

private def onDotClicked(
    fullGraph: Signal[ViewerGraph],
    tabState:  ViewerState
) =
  onClick.compose(
    _.sample(
      fullGraph,
      tabState.activeSymbols.signal,
      tabState.diagramOptionsV
    )
  ) --> { (fullDiagram: ViewerGraph, activeSymbols, options: DiagramOptions) =>
    dom.window.navigator.clipboard.writeText(
      fullDiagram
        .subgraph(activeSymbols.keySet)
        .toDot(
          "",
          diagramOptions = options
        )
    )
  }
