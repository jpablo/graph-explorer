package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.widgets.*
import org.jpablo.graphexplorer.viewer.widgets.Icons.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.backends.graphviz.DotExamples.examples

def Toolbar(projectName: Signal[String], commands: Commands, state: ViewerState) =
  val hiddenNodesIsEmpty =
    state.hiddenElements.signal.map(_.isEmpty)

  div(
    idAttr := "toolbar",
    cls    := "floating-toolbar",
    // -------- Navigation --------
    div(
      cls := "breadcrumbs text-md py-0",
      ul(
        li(
          a(cls := "link", title := "Home", span().houseIcon, onClick --> commands.navigateHome.action())
        ),
        li(
          a(
            cls   := "link",
            title := "Change title",
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
    Dropdown(
      placeholderText = "Copy as",
      options         = commands.sections.exportAs.map(cmd => cmd.title -> cmd.action),
      onClickHandler   = _ --> (command => command())
    ),
    Dropdown(
      placeholderText = "Examples",
      options         = examples.toSeq,
      onClickHandler =
        _.flatMap(FetchStream.get(_)) --> { source =>
          state.showAllNodes()
          state.sourceText.set(source)
        }
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
