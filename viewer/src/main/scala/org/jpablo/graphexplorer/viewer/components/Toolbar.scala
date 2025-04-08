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
  import commands.{all, routerCmds, sections}

  val hiddenNodesIsEmpty =
    state.hiddenElements.signal.map(_.isEmpty)

  def shapePreview(shape: Shape) =
    ShapePreview(shape, 20).get()

  val defaultShapePreview =
    state.nodeShape.map(shapePreview)

  val shapePreviews: Seq[MenuOption[() => Unit]] =
    Seq(Shape.box, Shape.circle, Shape.ellipse, Shape.diamond, Shape.star).map: shape =>
      MenuOption(
        elem = shapePreview(shape),
        value = () => state.addNodeWithSmartConnection(Attributes.of(Shape -> shape))
      )

  def filteredMenu(cmds: Command*) =
    state.selection.signal.map: selection =>
      cmds
        .filter(_.isVisible(selection))
        .map: cmd =>
          MenuOption(
            elem = cmd.title,
            value = cmd.action,
            extra = Some(cmd.titleWithShortcut)
          )

  div(
    idAttr := "toolbar",
    cls    := "floating-toolbar",
    // -------- Navigation --------
    div(
      cls := "breadcrumbs text-md py-0",
      ul(
        li(
          a(cls := "link", title := "Home", span().houseIcon, onClick --> routerCmds.navigateHome.action())
        ),
        li(
          a(
            cls   := "link",
            title := "Change title",
            text <-- projectName,
            onClick --> all.changeProjectName.action()
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
        child <-- defaultShapePreview.map(icon => span(icon).toTooltip(all.addNode.titleWithShortcut)),
        onClick --> all.addNode.action()
      ).tiny,
      DropdownHeader(
        title = emptyMod,
        icon = i().threeDotsVertical,
        join = true,
        Menu(options = Signal.fromValue(shapePreviews), onClickHandler = _ --> (action => action()))
          .amend(cls := "items-center")
      ).amend(cls := "dropdown-center ml-[-1px]")
    ),
    // -------- show all --------
    Button(
      all.showAll.title,
      cls := "btn-primary",
      disabled <-- hiddenNodesIsEmpty,
      onClick --> all.showAll.action()
    ).tiny.toTooltip(all.showAll.titleWithShortcut),
    // -------- actions --------
    Dropdown(
      title = span("Add"),
      options = filteredMenu(
        all.addNode,
        all.addBackwardsNode
      ),
      onClickHandler = _ --> (action => action())
    ),
    Dropdown(
      title = span("Select"),
      options = filteredMenu(
        all.selectAll,
        all.selectAllNodes,
        all.selectAllArrows,
        all.selectAllGroups,
        all.selectGroupMembers,
        all.selectAllSuccessors,
        all.selectDirectSuccessors,
        all.selectAllPredecessors,
        all.selectDirectPredecessors
      ),
      onClickHandler = _ --> (action => action())
    ),
    Dropdown(
      title = span("Actions"),
      options = filteredMenu(
        all.group,
        all.ungroup,
        all.moveToGroup,
        all.hideSelection,
        all.keep,
        all.delete,
        all.duplicate,
        all.zoomIntoGroup,
        all.editLabel,
        all.rootsOnly,
        all.hideAllNodes,
        all.showAllSuccessors,
        all.showDirectSuccessors,
        all.showAllPredecessors,
        all.showDirectPredecessors
      ),
      onClickHandler = _ --> (action => action())
    ),
    Dropdown(
      title = span("Copy as"),
      options = filteredMenu(sections.exportAs*),
      onClickHandler = _ --> (action => action())
    ),
    // -------- examples --------
    Dropdown(
      title = span("Examples"),
      options = Signal.fromValue(examples.toSeq.map((a, b) => MenuOption(a, b))),
      onClickHandler =
        _.flatMap(FetchStream.get(_)) --> { source =>
          state.showAll()
          state.sourceText.set(source)
        }
    ),
    CommandsPanel(state, commands),
    // ---------- Undo/Redo ----------
    Join(
      Button(
        span(cls := "bi bi-arrow-counterclockwise").toTooltip(all.undo.titleWithShortcut),
        onClick --> all.undo.action()
      ).tiny,
      Button(
        span(cls := "bi bi-arrow-clockwise").toTooltip(all.redo.titleWithShortcut),
        onClick --> all.redo.action()
      ).tiny
    ),
    Join(
      Button(
        span(cls := "bi bi-question-circle").toTooltip(all.helpKeyboardShortcuts.titleWithShortcut),
        onClick --> all.helpKeyboardShortcuts.action()
      ).tiny,
      a(
        cls    := "btn btn-xs",
        href   := "https://github.com/jpablo/graph-explorer/tree/viewer",
        target := "_blank",
        i(cls := "bi bi-github")
      )
    )
  )
