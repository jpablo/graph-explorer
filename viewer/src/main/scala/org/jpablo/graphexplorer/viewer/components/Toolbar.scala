package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.backends.graphviz.DotExamples.examples
import org.jpablo.graphexplorer.viewer.components.attributes.previews.ShapePreview
import org.jpablo.graphexplorer.viewer.components.leftPanel.CommandsPanel
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Shape
import org.jpablo.graphexplorer.viewer.models.Attributes
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.*
import org.jpablo.graphexplorer.viewer.widgets.Icons.*

def Toolbar(projectName: Signal[String], commands: Commands, state: ViewerState) =
  val hiddenNodesIsEmpty =
    state.hiddenElements.signal.map(_.isEmpty)

  def shapePreview(shape: Shape) =
    ShapePreview(shape, 20).get()

  val defaultShapePreview =
    state.nodeShape.map(shapePreview)

  val shapePreviews = Seq(Shape.box, Shape.circle, Shape.ellipse, Shape.diamond, Shape.star).map: shape =>
    shapePreview(shape) -> (() => state.addNodeWithSmartConnection(Attributes.of(Shape -> shape)))

  div(
    idAttr := "toolbar",
    cls    := "floating-toolbar",
    // -------- Navigation --------
    div(
      cls := "breadcrumbs text-md py-0",
      ul(
        li(
          a(cls := "link", title := "Home", span().houseIcon, onClick --> commands.routerCmds.navigateHome.action())
        ),
        li(
          a(
            cls   := "link",
            title := "Change title",
            text <-- projectName,
            onClick --> commands.all.changeProjectName.action()
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
        child <-- defaultShapePreview.map(icon => span(icon).toTooltip(commands.all.addNode.titleWithShortcut)),
        onClick --> commands.all.addNode.action()
      ).tiny,
      DropdownHeader(
        title = emptyMod,
        icon = i().threeDotsVertical,
        join = true,
        Menu(options = shapePreviews, onClickHandler = _ --> (action => action()))
          .amend(cls := "items-center")
      ).amend(cls := "dropdown-center ml-[-1px]")
    ),
    // -------- show all --------
    Button(
      commands.all.showAllNodes.title,
      cls := "btn-primary",
      disabled <-- hiddenNodesIsEmpty,
      onClick --> commands.all.showAllNodes.action()
    ).tiny.toTooltip(commands.all.showAllNodes.titleWithShortcut),
    // -------- actions --------
    Dropdown(
      title = span("Copy as"),
      options = commands.sections.exportAs.map(cmd => cmd.title -> cmd.action),
      onClickHandler = _ --> (action => action())
    ),
    Dropdown(
      title = span("Select"),
      options = Seq(
        commands.all.selectAll,
        commands.all.selectAllNodes,
        commands.all.selectAllArrows,
        commands.all.selectAllGroups,
      ).map(cmd => cmd.title -> cmd.action),
      onClickHandler = _ --> (action => action())
    ),
    // -------- examples --------
    Dropdown(
      title = span("Examples"),
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
        span(cls := "bi bi-arrow-counterclockwise").toTooltip(commands.all.undo.titleWithShortcut),
        onClick --> commands.all.undo.action()
      ).tiny,
      Button(
        span(cls := "bi bi-arrow-clockwise").toTooltip(commands.all.redo.titleWithShortcut),
        onClick --> commands.all.redo.action()
      ).tiny
    ),
    Join(
      Button(
        span(cls := "bi bi-question-circle").toTooltip(commands.all.helpKeyboardShortcuts.titleWithShortcut),
        onClick --> commands.all.helpKeyboardShortcuts.action()
      ).tiny,
      a(
        cls    := "btn btn-xs",
        href   := "https://github.com/jpablo/graph-explorer/tree/viewer",
        target := "_blank",
        i(cls := "bi bi-github")
      )
    )
  )
