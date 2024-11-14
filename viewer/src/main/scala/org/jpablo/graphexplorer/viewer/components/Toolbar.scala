package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import com.raquo.laminar.nodes.ReactiveHtmlElement
import io.laminext.syntax.core.*
import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.*
import org.jpablo.graphexplorer.viewer.widgets.Icons.*
import org.scalajs.dom.window

def Toolbar(
    state:      ViewerState,
    fitDiagram: EventBus[Unit],
    router:     Router
) =
  import state.eventHandlers.*

  val drawerId = s"drawer-id"
  val writeTextToClipboard = window.navigator.clipboard.writeText
  div(
    idAttr := "toolbar",
    // -------- Navigation --------
    div(
      cls := "breadcrumbs font-bold py-0",
      ul(
        li(
          a(
            cls := "gap-2",
            span().folderIcon,
            "Graph Explorer",
            onClick --> router.navigateTo(Route.Home)
          )
        ),
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
          cls("btn-active") <-- state.leftPanelVisible,
          onClick --> state.leftPanelVisible.toggle()
        ).asBtn.tiny.ghost.layoutSidebarIcon
      )
    ),
    // -------- actions toolbar --------
    div(
      cls := "dropdown",
      div(tabIndex := 0, role := "button", span().threeDotsVertical).asBtn.tiny.ghost,
      ul(
        tabIndex := 0,
        cls      := "dropdown-content menu bg-base-100 rounded-box z-[1] w-52 p-2 shadow",
        li(a("roots", onClick.keepRootsOnly)),
        li(a("show all", onClick --> state.showAllNodes())),
        li(a("hide all", onClick.hideAllNodes)),
        li(cls := "menu-title", hr()),
        li(a("Diagram attributes", onClick --> state.diagramAttributesVisible.toggle())),
      )
    ),
    div(
      cls := "dropdown dropdown-hover",
      div(tabIndex := 0, role := "button", cls := "whitespace-nowrap", "Copy as").asBtn.tiny,
      ul(
        tabIndex := 0,
        cls      := "dropdown-content menu bg-base-100 rounded-box z-[1] w-52 p-2 shadow",
        li(a("Svg", onClick.copyAsFullDiagramSVG(writeTextToClipboard))),
        li(a("Dot", onClick.copyAsDOT(writeTextToClipboard))),
        li(a("Json Dot AST", onClick.copyAsJSON(writeTextToClipboard)))
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
