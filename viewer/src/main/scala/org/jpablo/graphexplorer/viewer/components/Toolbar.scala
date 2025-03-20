package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.widgets.*
import org.jpablo.graphexplorer.viewer.widgets.Icons.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.backends.graphviz.DotExamples.examples
import org.jpablo.graphexplorer.viewer.components.leftPanel.CommandsPanel

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
    Button(span().biSquareIcon, onClick --> commands.addNode.action())
      .tiny.toTooltip(commands.addNode.titleWithShortcut),
    // -------- show all --------
    Button(
      commands.showAll.title,
      cls := "btn-primary",
      disabled <-- hiddenNodesIsEmpty,
      onClick --> commands.showAll.action()
    ).tiny.toTooltip(commands.showAll.titleWithShortcut),
    // -------- actions toolbar --------
    Dropdown(
      placeholderText = "Copy as",
      options         = commands.sections.exportAs.map(cmd => cmd.title -> cmd.action),
      onClickHandler  = _ --> (command => command())
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
    CommandsPanel(state, commands),
    // ---------- Undo/Redo ----------
    Join(
      Button(
        span(cls := "bi bi-arrow-counterclockwise").toTooltip(commands.undo.titleWithShortcut),
        onClick --> commands.undo.action()
      ).tiny,
      Button(
        span(cls := "bi bi-arrow-clockwise").toTooltip(commands.redo.titleWithShortcut),
        onClick --> commands.redo.action()
      ).tiny
    ),
    Join(
      Button(
        span(cls := "bi bi-question-circle").toTooltip(commands.keyboardShortcuts.titleWithShortcut),
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
