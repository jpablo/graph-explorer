package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
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

  val writeTextToClipboard = window.navigator.clipboard.writeText
  val inputId = s"toggle-diagram-elements"

  div(
    idAttr := "toolbar",
    cls    := "bg-base-100/90",
    // -------- Navigation --------
    div(
      cls := "breadcrumbs font-bold py-0",
      a(
        cls := "mr-2 link",
        span().chevronLeftIcon,
        onClick --> router.navigateTo(Route.Home)
      ),
      a(
        cls := "link",
        text <-- state.project.name.signal,
        onClick --> { _ =>
          val newName = window.prompt("Enter project Name", state.project.name.now())
          if newName != null then
            state.project.name.set(newName)
        }
      )
    ),
    // -------- new node button --------
    Tooltip(
      text = "New Node (n)",
      cls := "tooltip-bottom",
      Button(span().biSquareIcon, onClick --> state.addNode()).tiny
    ),
    // -------- actions toolbar --------
    div(
      cls := "dropdown dropdown-hover",
      div(tabIndex := 0, role := "button", span("view"), i(cls := "bi bi-chevron-down")).asBtn.tiny,
      ul(
        tabIndex := 0,
        cls      := "dropdown-content menu bg-base-100 rounded-box z-[1] w-52 p-2 shadow-lg",
        li(a("roots", onClick.keepRootsOnly)),
        li(a("show all", onClick --> state.showAllNodes())),
        li(a("hide all", onClick.hideAllNodes))
      )
    ),
    div(
      cls := "dropdown dropdown-hover",
      div(tabIndex := 0, role := "button", cls := "whitespace-nowrap", span("Copy as"), i(cls := "bi bi-chevron-down"))
        .asBtn.tiny,
      ul(
        tabIndex := 0,
        cls      := "dropdown-content menu bg-base-100 rounded-box z-[1] w-52 p-2 shadow-lg",
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
    // ---------- Undo/Redo ----------
    Join(
      Button(
        i(cls := "bi bi-arrow-counterclockwise"),
        title := "Undo",
        onClick --> state.undoEvent.emit(())
      ).tiny,
      Button(
        i(cls := "bi bi-arrow-clockwise"),
        title := "Redo",
        onClick --> state.redoEvent.emit(())
      ).tiny
    ),
    Join(
      Button(
        i(cls := "bi bi-question-circle"),
        title := "Help - Keyboard Shortcuts",
        onClick --> state.shortcutsModalOpen.set(true)
      ).tiny,
      a(
        cls    := "btn btn-xs",
        href   := "https://github.com/jpablo/graph-explorer/tree/viewer",
        target := "_blank",
        i(cls := "bi bi-github")
      )
    )
  )
