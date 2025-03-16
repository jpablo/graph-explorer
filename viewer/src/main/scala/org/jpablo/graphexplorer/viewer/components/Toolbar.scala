package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.widgets.*
import org.jpablo.graphexplorer.viewer.widgets.Icons.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.state.ViewerState

def Toolbar(projectName: Signal[String], commands: Commands, state: ViewerState) =
  val hiddenNodesIsEmpty =
    state.hiddenElements.signal.map(_.isEmpty)

  div(
    idAttr := "toolbar",
    cls    := "bg-base-100/90",
    // -------- Navigation --------
    div(
      cls := "breadcrumbs font-bold py-0",
      ul(
        li(
          a(cls := "mr-2 link", span().houseIcon, onClick --> commands.navigateHome.action())
        ),
        li(
          a(
            cls := "link",
            text <-- projectName,
            onClick --> commands.changeProjectName.action()
          )
        )
      )
    ),
    span(cls := "divider divider-horizontal mx-0"),
    // -------- new node button --------
    Tooltip(
      text = commands.addNode.titleWithShortcut,
      cls := "tooltip-bottom",
      Button(span().biSquareIcon, onClick --> commands.addNode.action()).tiny
    ),
    // -------- show all --------
    Tooltip(
      text = commands.showAll.titleWithShortcut,
      cls := "tooltip-bottom",
      Button(
        commands.showAll.title,
        cls := "btn-primary",
        disabled <-- hiddenNodesIsEmpty,
        onClick --> commands.showAll.action()
      ).tiny
    ),
    // -------- actions toolbar --------
    div(
      cls := "dropdown dropdown-hover",
      div(tabIndex := 0, role := "button", cls := "whitespace-nowrap", span("Copy as"), i(cls := "bi bi-chevron-down"))
        .asBtn.tiny,
      ul(
        tabIndex := 0,
        cls      := "dropdown-content menu bg-base-100 rounded-box z-[1] w-52 p-2 shadow-lg",
        for cmd <- commands.sections.exportAs yield li(a(cmd.title, onClick --> cmd.action()))
      )
    ),
    // ---------- zoom ----------
    Join(
      Button(span().dashIcon, onClick --> commands.zoomOut.action()).tiny,
      Button(commands.fit.title, onClick --> commands.fit.action()).tiny,
      Button(span().plusIcon, onClick --> commands.zoomIn.action()).tiny
    ),
    // ---------- Undo/Redo ----------
    Join(
      Button(
        i(cls := "bi bi-arrow-counterclockwise"),
        title := commands.undo.title,
        onClick --> commands.undo.action()
      ).tiny,
      Button(
        i(cls := "bi bi-arrow-clockwise"),
        title := commands.redo.title,
        onClick --> commands.redo.action()
      ).tiny
    ),
    Join(
      Button(
        i(cls := "bi bi-question-circle"),
        title := commands.keyboardShortcuts.title,
        onClick --> commands.keyboardShortcuts.action()
      ).tiny,
      a(
        cls    := "btn btn-xs",
        href   := "https://github.com/jpablo/graph-explorer/tree/viewer",
        target := "_blank",
        i(cls := "bi bi-github")
      )
    )
  )
