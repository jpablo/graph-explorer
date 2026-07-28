package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.widgets.{SelectVariant, TooltipPos, soft}
import com.raquo.laminar.api.features.unitArrows
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.backends.graphviz.DotExamples.examples
import org.jpablo.graphexplorer.viewer.components.attributes.previews.ShapePreview
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Shape
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph.minimal.defaultNodeTheme
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

  val defaultShapePreview: ReactiveSvgElement[SVG] | String = 
    shapePreview((defaultNodeTheme.getAs(Shape): Shape))

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
      // The mirror image of the right toolbar's section toggles, and drawn the same way:
      // both open a panel, so both are an icon that stays pressed while its panel is out.
      IconToggle(
        "bi-layout-sidebar",
        "Toggle Library",
        state.leftPanelVisible,
        mods = idAttr := "toggle-library"
      ),
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
            IconButtonTitled("bi-pencil text-[.65rem]", "Change title")(all.changeProjectName.execute())
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
          defaultShapePreview match
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
          _.flatMap(example => FetchStream.get(example.path).map(example -> _)) --> {
            case (example, source) =>
              state.showAll()
              state.setDiagramFormat(example.format)
              state.sourceText.set(source)
          }
      ).amend(cls := "hidden lg:block"),
      // -------- show all --------
      Button(
        all.showAll.shortLabel,
        disabled <-- hiddenNodesIsEmpty,
        onClick --> all.showAll.execute()
      ).tiny.soft.primary.toTooltip(all.showAll.labelWithShortcut),
      CommandsPanel(state, commands).amend(cls := "hidden lg:block")
    ),
    div(
      cls := "navbar-end gap-2",
      // Two clusters, not eight loose buttons: history is one thought, "about this app" is
      // another. The gap between clusters is what separates them — no dividers needed.
      div(
        cls := "flex items-center gap-0.5",
        IconButton("bi-arrow-counterclockwise", all.undo.labelWithShortcut)(all.undo.execute()),
        IconButton("bi-arrow-clockwise", all.redo.labelWithShortcut)(all.redo.execute())
      ),
      div(
        cls := "flex items-center gap-0.5",
        // tooltip-end (daisyUI 5.6 alignment): these sit at the window's right
        // edge, where a centre-aligned bubble clips off-screen.
        IconButton("bi-question-circle", all.helpKeyboardShortcuts.labelWithShortcut, tipPos = TooltipPos.bottomEnd)(
          all.helpKeyboardShortcuts.execute()
        ),
        IconButton("bi-link-45deg", all.copyShareURL.labelWithShortcut, tipPos = TooltipPos.bottomEnd)(all.copyShareURL.execute()),
        IconButton("bi-info-circle", all.openAboutDialog.labelWithShortcut, tipPos = TooltipPos.bottomEnd)(all.openAboutDialog.execute()),
        IconLink("bi-github", "Source on GitHub", "https://github.com/jpablo/graph-explorer/tree/viewer")
      ),
      // -------- Theme Selector --------
      // Ghost, like the editor's language select: a theme is a preference you set once, so it
      // should not draw a box around itself in a bar full of actions.
      Select(
        placeholderText = Some(s"Select theme"),
        options = themeOptions.collect { case r: MenuOption[String] => (r.value, r.value) },
        onChange.mapToValue --> { theme => state.currentTheme.set(Some(theme)) },
        value <-- state.currentTheme.signal.map(_.getOrElse("light")),
        SelectVariant.ghost,
        cls := "w-24 theme-select"
      )
    )
  )
