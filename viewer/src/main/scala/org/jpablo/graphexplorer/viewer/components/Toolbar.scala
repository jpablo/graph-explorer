package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.widgets.*
import org.jpablo.graphexplorer.viewer.widgets.Icons.*
import com.raquo.laminar.api.features.unitArrows

def Toolbar(projectName: Signal[String], commands: Commands) =
  div(
    idAttr := "toolbar",
    cls    := "bg-base-100/90",
    // -------- Navigation --------
    div(
      cls := "breadcrumbs font-bold py-0",
      a(cls := "mr-2 link", span().chevronLeftIcon, onClick --> commands.navigateHome.action()),
      a(
        cls := "link",
        text <-- projectName,
        onClick --> commands.changeProjectName.action()
      )
    ),
    // -------- new node button --------
    Tooltip(
      text = commands.addNode.titleWithShortcut,
      cls := "tooltip-bottom",
      Button(span().biSquareIcon, onClick --> commands.addNode.action()).tiny
    ),
    // -------- actions toolbar --------
    div(
      cls := "dropdown dropdown-hover",
      div(tabIndex := 0, role := "button", span("View"), i(cls := "bi bi-chevron-down")).asBtn.tiny,
      ul(
        tabIndex := 0,
        cls      := "dropdown-content menu bg-base-100 rounded-box z-[1] w-52 p-2 shadow-lg",
        for cmd <- commands.sections.view yield li(a(cmd.title, onClick --> cmd.action()))
      )
    ),
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
    // ----------
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
