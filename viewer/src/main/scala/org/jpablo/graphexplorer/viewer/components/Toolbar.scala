package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import com.raquo.laminar.nodes.ReactiveHtmlElement
import io.laminext.syntax.core.*
import org.jpablo.graphexplorer.router.navigateToHome
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.*
import org.jpablo.graphexplorer.viewer.widgets.Icons.*
import org.scalajs.dom.window

def Toolbar(
    state:      ViewerState,
    fitDiagram: EventBus[Unit]
) =
  import state.eventHandlers.*

  val drawerId = s"drawer-id"
  div(
    idAttr := "toolbar",
    // -------- Navigation --------
    div(
      cls := "breadcrumbs font-bold py-0",
      ul(
        li(a("Graph Explorer", onClick --> navigateToHome())),
        li(
          a(
            cls := "gap-2",
            span().boxSeamIcon,
            text <-- state.project.name.signal,
            onClick --> { _ =>
              val newName = window.prompt("Enter project Name", state.project.name.now())
              if newName != null then
                state.project.name.set(newName)
            }
          )
        )
      )
    ),
    // -------- Left panel toggle --------
    Join(
      Tooltip(
        text = "Diagram elements",
        cls := "flex-none",
        input(idAttr := drawerId, tpe := "checkbox", cls := "drawer-toggle"),
        label(
          forId := drawerId,
          cls   := "btn btn-ghost",
          cls("btn-active") <-- state.leftPanelVisible,
          onClick --> state.leftPanelVisible.toggle()
        ).tiny.layoutSidebarIcon
      )
    ),
    // -------- actions toolbar --------
    Join(
      Button("roots", onClick.keepRootsOnly).tiny,
      Button("show all", onClick --> state.showAllNodes()).tiny,
      Button("hide all", onClick.hideAllNodes).tiny,
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
          li(a("Svg", onClick.copyAsFullDiagramSVG(window.navigator.clipboard.writeText))),
          li(a("Dot", onClick.copyAsDOT(window.navigator.clipboard.writeText))),
          li(a("Json Dot AST", onClick.copyAsJSON(window.navigator.clipboard.writeText)))
        )
      )
    ),
    // ----------
    Join(
      Button(span().dashIcon, onClick --> state.zoomValue.update(_ * 0.9)).tiny,
      Button("fit", onClick --> fitDiagram.emit(())).tiny,
      Button(span().plusIcon, onClick --> state.zoomValue.update(_ * 1.1)).tiny
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
    ),
    Join(
      a(
        cls    := "btn btn-xs",
        href   := "https://github.com/jpablo/graph-explorer/tree/viewer",
        target := "_blank",
        i(cls := "bi bi-github")
      )
    )
  )
