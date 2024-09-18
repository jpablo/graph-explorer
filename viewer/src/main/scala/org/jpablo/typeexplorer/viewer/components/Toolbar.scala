package org.jpablo.typeexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import com.raquo.laminar.nodes.ReactiveHtmlElement
import io.laminext.syntax.core.*
import org.jpablo.typeexplorer.viewer.state.ViewerState
import org.jpablo.typeexplorer.viewer.widgets.*
import org.jpablo.typeexplorer.viewer.widgets.Icons.*
import org.scalajs.dom

def Toolbar(
    state:      ViewerState,
    fitDiagram: EventBus[Unit]
) =
  val drawerId = s"drawer-id"
  div(
    cls := "shadow bg-base-100 rounded-box flex items-center gap-4 p-0.5 absolute top-1 left-2/4 -translate-x-2/4 z-10",
    idAttr := "toolbar",
    // -------- package selector --------
    Join(
      Tooltip(
        text = "Select visible nodes",
        input(idAttr := drawerId, tpe := "checkbox", cls := "drawer-toggle"),
        label(
          forId := drawerId,
          cls   := "btn btn-ghost btn-sm bi bi-layout-sidebar",
          cls("btn-active") <-- state.sideBarVisible,
          onClick --> state.sideBarVisible.toggle()
        )
      ).amend(cls := "flex-none")
    ),
    // -------- actions toolbar --------
    Join(
      Button(
        "add all",
        onClick.compose(_.sample(state.allNodeIds).map(_.toSeq)) --> (state.visibleNodes.extend(_))
      ).tiny,
      Button("remove all", onClick --> state.visibleNodes.clear()).tiny,
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
              onClick.compose(_.sample(state.svgDotDiagram)) --> { diagram =>
                dom.window.navigator.clipboard.writeText(diagram.toSVGText)
              }
            )
          ),
          li(
            a(
              "dot",
              onClick.compose(_.sample(state.visibleDOT)) --> { dot =>
                dom.window.navigator.clipboard.writeText(dot.value)
              }
            )
          )
        )
      )
    ),
    // ----------
    Join(
      Button(span.dashIcon, onClick --> state.zoomValue.update(_ * 0.9)).tiny,
      Button("fit", onClick --> fitDiagram.emit(())).tiny,
      Button(span.plusIcon, onClick --> state.zoomValue.update(_ * 1.1)).tiny
    ),
    Join(
      input(
        tpe      := "range",
        cls      := "range range-xs pr-3",
        minAttr  := 0.25.toString,
        maxAttr  := 5.0.toString,
        stepAttr := "0.05",
        controlled(
          value <-- state.zoomValue.signal.map(_.toString),
          onInput.mapToValue.map(_.toDouble) --> state.zoomValue
        )
      )
    )
  )
