package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.backends.graphviz.DotExamples.examples
import org.jpablo.graphexplorer.viewer.components.leftPanel.CommandsPanel
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Shape
import org.jpablo.graphexplorer.viewer.models.Attributes
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.*
import org.jpablo.graphexplorer.viewer.widgets.Icons.*

def Toolbar(projectName: Signal[String], commands: Commands, state: ViewerState) =
  val hiddenNodesIsEmpty =
    state.hiddenElements.signal.map(_.isEmpty)

  val svgBox =
    svg.svg(
      svg.width   := 24.toString,
      svg.height  := 16.toString,
      svg.viewBox := "0 0 24 16",
      svg.path(
        svg.d := "M22 1a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H2a1 1 0 0 1-1-1V2a1 1 0 0 1 1-1zM2 0a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h20a2 2 0 0 0 2-2V2a2 2 0 0 0-2-2z"
      )
    )

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
    div(
      cls := "join inline",
      Button(
        cls := "join-item",
        span().biSquareIcon.toTooltip(commands.addNode.titleWithShortcut),
        onClick --> commands.addNode.action()
      ).tiny,
      DropdownHeader(
        title = emptyMod,
        icon  = i().threeDotsVertical,
        join  = true,
        Menu(
          options = Seq(
            span(svgBox)        -> (() => state.addNodeWithSmartConnection(Attributes.of(Shape -> Shape.box))),
            span().circleIcon   -> (() => state.addNodeWithSmartConnection(Attributes.of(Shape -> Shape.circle))),
            span().diamondIcon  -> (() => state.addNodeWithSmartConnection(Attributes.of(Shape -> Shape.diamond)))
          ),
          onClickHandler = _ --> (action => action())
        ).amend(cls := "items-center")
      ).amend(cls := "dropdown-center")
    ),
    // -------- show all --------
    Button(
      commands.showAll.title,
      cls := "btn-primary",
      disabled <-- hiddenNodesIsEmpty,
      onClick --> commands.showAll.action()
    ).tiny.toTooltip(commands.showAll.titleWithShortcut),
    // -------- actions toolbar --------
    Dropdown(
      title          = span("Copy as"),
      options        = commands.sections.exportAs.map(cmd => cmd.title -> cmd.action),
      onClickHandler = _ --> (action => action())
    ),
    Dropdown(
      title   = span("Examples"),
      options = examples.toSeq,
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
