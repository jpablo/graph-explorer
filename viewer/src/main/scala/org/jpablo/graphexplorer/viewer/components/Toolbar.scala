package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.widgets.*
import org.jpablo.graphexplorer.viewer.widgets.Icons.*

def Toolbar(projectName: Signal[String], commands: Commands) =
  div(
    idAttr := "toolbar",
    cls    := "bg-base-100/90",
    // -------- Navigation --------
    div(
      cls := "breadcrumbs font-bold py-0",
      a(cls := "mr-2 link", span().chevronLeftIcon, commands.navigateHome.action(onClick)),
      a(
        cls := "link",
        text <-- projectName,
        commands.changeProjectName.action(onClick)
      )
    ),
    // -------- new node button --------
    Tooltip(
      text = commands.addNode.titleWithShortcut,
      cls := "tooltip-bottom",
      Button(span().biSquareIcon, commands.addNode.action(onClick)).tiny
    ),
    // -------- actions toolbar --------
    div(
      cls := "dropdown dropdown-hover",
      div(tabIndex := 0, role := "button", span("View"), i(cls := "bi bi-chevron-down")).asBtn.tiny,
      ul(
        tabIndex := 0,
        cls      := "dropdown-content menu bg-base-100 rounded-box z-[1] w-52 p-2 shadow-lg",
        for cmd <- commands.sections.view yield li(a(cmd.title, cmd.action(onClick)))
      )
    ),
    div(
      cls := "dropdown dropdown-hover",
      div(tabIndex := 0, role := "button", cls := "whitespace-nowrap", span("Copy as"), i(cls := "bi bi-chevron-down"))
        .asBtn.tiny,
      ul(
        tabIndex := 0,
        cls      := "dropdown-content menu bg-base-100 rounded-box z-[1] w-52 p-2 shadow-lg",
        for cmd <- commands.sections.exportAs yield li(a(cmd.title, cmd.action(onClick)))
      )
    ),
    // ----------
    Join(
      Button(span().dashIcon, commands.zoomOut.action(onClick)).tiny,
      Button(commands.fit.title, commands.fit.action(onClick)).tiny,
      Button(span().plusIcon, commands.zoomIn.action(onClick)).tiny
    ),
    // ---------- Undo/Redo ----------
    Join(
      Button(
        i(cls := "bi bi-arrow-counterclockwise"),
        title := commands.undo.title,
        commands.undo.action(onClick)
      ).tiny,
      Button(
        i(cls := "bi bi-arrow-clockwise"),
        title := commands.redo.title,
        commands.redo.action(onClick)
      ).tiny
    ),
    Join(
      Button(
        i(cls := "bi bi-question-circle"),
        title := commands.keyboardShortcuts.title,
        commands.keyboardShortcuts.action(onClick)
      ).tiny,
      a(
        cls    := "btn btn-xs",
        href   := "https://github.com/jpablo/graph-explorer/tree/viewer",
        target := "_blank",
        i(cls := "bi bi-github")
      )
    )
  )
