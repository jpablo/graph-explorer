package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.backends.graphviz.DotExamples.examples
import org.jpablo.graphexplorer.viewer.components.attributes.previews.ShapePreview
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Shape
import org.jpablo.graphexplorer.viewer.models.Attributes
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.*
import org.jpablo.graphexplorer.viewer.widgets.Icons.*
import org.jpablo.graphexplorer.viewer.widgets.MenuEntry.{MenuOption, Sep}
import org.scalajs.dom.svg.SVG

def Toolbar(projectName: Signal[String], commands: Commands, state: ViewerState): Div =
  import commands.{all, routerCmds, sections}

  val hiddenNodesIsEmpty =
    state.hiddenElements.signal.map(_.isEmpty)

  def shapePreview(shape: Shape): ReactiveSvgElement[SVG] | String =
    ShapePreview(shape, 16).map(_()).getOrElse(shape.toString)

  val defaultShapePreview =
    state.nodeShape.map(shapePreview)

  val shapePreviews: Seq[MenuEntry[() => Unit]] =
    Seq(Shape.box, Shape.circle, Shape.ellipse, Shape.diamond, Shape.star)
      .map: shape =>
        MenuOption(
          elem = shapePreview(shape),
          value = () => state.createNodeMaybePrompt(Attributes.of(Shape -> shape)),
          description = None,
          shortcut = None
        )

  def filteredMenu(cmds: Command[?] | Sep.type*) =
    state.selection.signal.map: selection =>
      cmds
        .filter:
          case cmd: Command[?] => cmd.isVisible(selection)
          case _               => true
        .map:
          case cmd: Command[?] =>
            MenuOption(
              elem = cmd.shortLabel,
              value = () => cmd.execute(),
              description = Some(cmd.description.getOrElse(cmd.shortLabel)),
              shortcut = cmd.shortcut.map(_.toList)
            )
          case _ => Sep

  val daisyThemes = Seq(
    "light",
    "dark",
    "abyss",
    "acid",
    "aqua",
    "autumn",
    "black",
    "bumblebee",
    "business",
    "caramellatte",
    "cmyk",
    "coffee",
    "corporate",
    "cupcake",
    "cyberpunk",
    "dim",
    "dracula",
    "emerald",
    "fantasy",
    "forest",
    "garden",
    "halloween",
    "lemonade",
    "lofi",
    "luxury",
    "night",
    "nord",
    "pastel",
    "retro",
    "silk",
    "sunset",
    "synthwave",
    "valentine",
    "winter",
    "wireframe"
  )

  val themeOptions: Seq[MenuEntry[String]] =
    daisyThemes.map(theme => MenuOption(theme, theme, None, None))

  div(
    idAttr := "toolbar",
    cls    := "navbar",
    // -------- Navigation --------
    div(
      cls := "navbar-start gap-4",
      //
      Button(
        idAttr := "toggle-library",
        title  := "Toggle Library",
        cls("btn-active") <-- state.leftPanelVisible,
        span().layoutSidebarIcon,
        onClick --> state.leftPanelVisible.update(!_)
      ).tiny,
      // -------- Breadcrumbs --------
      div(
        cls := "breadcrumbs text-md py-0",
        ul(
          li(
            a(cls := "text-xs", title := "Home", span().houseIcon, onClick --> routerCmds.navigateHome.execute())
          ),
          li(
            cls := "text-sm",
            text <-- projectName,
            a(
              cls   := "btn btn-xs btn-circle btn-ghost ml-[1px] w-4 h-4",
              title := "Change title",
              i(cls := "text-[.6rem] text-base-content/50").pencilIcon,
              onClick --> all.changeProjectName.execute()
            )
          )
        )
      )
    ),
    // -------- new node button --------
    div(
      cls := "navbar-center gap-2",
      // ---
      div(
        cls := "flex-nowrap",
        Button(
          cls := "gap-1 pl-1",
          i(cls := "bi bi-plus"),
          child <-- defaultShapePreview.map:
            case svg: ReactiveSvgElement[SVG] => span(svg)
            case str: String                  => span(str)
          ,
          onClick --> all.newNode.execute()
        ).tiny.toTooltip(all.newNode.labelWithShortcut),
        // --- extra options ---
        Dropdown(
          title = emptyMod,
          options = Signal.fromValue(shapePreviews),
          onClickHandler = _ --> (action => action()),
          icon = i().threeDotsVertical,
          join = true,
          menuCls = "items-center"
        ).amend(cls := "dropdown-center ml-[-1px]")
      ),
      // -------- actions --------
      Dropdown(
        title = span("Add"),
        options = filteredMenu(
          all.newNode,
          all.newBackwardsNode
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
          all.showAll,
          all.keep,
          all.delete,
          all.duplicate,
          all.combineIntoRecord,
          all.splitRecord,
          all.transposeRecord,
          all.reverseArrows,
          all.reverseArrowsStyle,
          all.zoomIntoGroup,
          all.editLabel,
          all.resetSelectionAttributes,
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
        options = Signal.fromValue(examples.toSeq.map((a, b) => MenuOption(a, b, None, None))),
        onClickHandler =
          _.flatMap(FetchStream.get(_)) --> { source =>
            state.showAll()
            state.sourceText.set(source)
          }
      ).amend(cls := "hidden lg:block"),
      // -------- show all --------
      Button(
        all.showAll.shortLabel,
        cls := "btn-soft btn-primary",
        disabled <-- hiddenNodesIsEmpty,
        onClick --> all.showAll.execute()
      ).tiny.toTooltip(all.showAll.labelWithShortcut),
      CommandsPanel(state, commands).amend(cls := "hidden lg:block")
    ),
    div(
      cls := "navbar-end gap-0",
      // ---------- Undo/Redo ----------
      div(
        cls := "join",
        Button(
          cls := "text-base join-item",
          span(cls := "bi bi-arrow-counterclockwise"),
          onClick --> all.undo.execute()
        ).tiny.ghost.toTooltip(all.undo.labelWithShortcut),
        Button(
          cls := "text-base join-item",
          span(cls := "bi bi-arrow-clockwise"),
          onClick --> all.redo.execute()
        ).tiny.ghost.toTooltip(all.redo.labelWithShortcut)
      ),
      Button(
        cls := "text-base",
        span(cls := "bi bi-question-circle"),
        onClick --> all.helpKeyboardShortcuts.execute()
      ).tiny.ghost.toTooltip(all.helpKeyboardShortcuts.labelWithShortcut, "tooltip-left"),
      Button(
        cls := "text-base",
        span(cls := "bi bi-link-45deg"),
        onClick --> all.copyShareURL.execute()
      ).tiny.ghost.toTooltip(all.copyShareURL.labelWithShortcut, "tooltip-bottom"),
      Button(
        cls := "text-base",
        span(cls := "bi bi-info-circle"),
        onClick --> all.openAboutDialog.execute()
      ).tiny.ghost.toTooltip(all.openAboutDialog.labelWithShortcut, "tooltip-left"),
      a(
        cls    := "text-base",
        cls    := "btn btn-xs btn-ghost",
        href   := "https://github.com/jpablo/graph-explorer/tree/viewer",
        target := "_blank",
        span(cls := "bi bi-github")
      ),
      // -------- Theme Selector --------
      Select(
        placeholderText = Some(s"Select theme"),
        options = themeOptions.collect { case r: MenuOption[String] => (r.value, r.value) },
        onChange.mapToValue --> { theme => state.currentTheme.set(Some(theme)) },
        value <-- state.currentTheme.signal.map(_.getOrElse("light")),
        cls := "w-24"
      )
    )
  )
