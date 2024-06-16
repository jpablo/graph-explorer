package org.jpablo.typeexplorer.viewer.plantUML

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import com.raquo.laminar.nodes.ReactiveHtmlElement
import com.softwaremill.quicklens.*
import org.jpablo.typeexplorer.viewer.graph.InheritanceGraph
//import org.jpablo.typeexplorer.shared.inheritance.{InheritanceGraph, toPlantUML}
import org.jpablo.typeexplorer.viewer.plantUML.state.InheritanceTabState
import org.jpablo.typeexplorer.viewer.widgets.*
import org.jpablo.typeexplorer.viewer.widgets.Icons.*
import org.jpablo.typeexplorer.viewer.widgets.{Join, Tooltip}
import org.scalajs.dom
import org.scalajs.dom.{HTMLDivElement, HTMLElement}

def Toolbar(
    fullGraph:  Signal[InheritanceGraph],
    tabState:   InheritanceTabState,
    zoomValue:  Var[Double],
    fitDiagram: EventBus[Unit]
//    appConfigDialogOpenV: Var[Boolean]
) =
  val drawerId = s"drawer-tab-${tabState.pageId}"
  div(
    cls := "shadow bg-base-100 rounded-box flex items-center gap-4 p-0.5 absolute top-1 left-2/4 -translate-x-2/4 z-10",
    // -------- package selector --------
    Join(
      Tooltip(
        text = "Package Selector",
        input(idAttr := drawerId, tpe := "checkbox", cls := "drawer-toggle"),
        label(
          forId := drawerId,
          cls   := "btn btn-ghost btn-sm bi bi-boxes"
//          onClick --> appConfigDialogOpenV.set(true)
        )
      ).amend(cls := "flex-none")
    ),
    // -------- fields and signatures --------
    Join(
      LabeledCheckbox(
        id        = "fields-checkbox-1",
        labelStr  = "fields",
        isChecked = tabState.diagramOptionsV.signal.map(_.showFields),
        clickHandler = tabState.diagramOptionsV
          .updater(_.modify(_.showFields).setTo(_)),
        toggle = true
      )
    ),
    // -------- actions toolbar --------
    Join(
      Button(
        "remove all",
        onClick --> tabState.activeSymbols.clear()
      ).tiny,
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
              onClick.compose(_.sample(tabState.inheritanceSvgDiagram)) --> { diagram =>
                dom.window.navigator.clipboard.writeText(diagram.toSVGText)
              }
            )
          ),
          li(
            a("plantuml", onPlantUMLClicked(fullGraph, tabState))
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

private def onPlantUMLClicked(
    fullGraph: Signal[InheritanceGraph],
    tabState:  InheritanceTabState
) =
  onClick.compose(
    _.sample(
      fullGraph,
      tabState.activeSymbols.signal,
      tabState.diagramOptionsV
    )
  ) --> { (fullDiagram: InheritanceGraph, activeSymbols, options) =>
    dom.window.navigator.clipboard.writeText(
      fullDiagram
        .subdiagram(activeSymbols.keySet)
        .toPlantUML(activeSymbols, options)
        .diagram
    )
  }
