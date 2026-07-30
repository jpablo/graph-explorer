package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.components.attributes.previews.ShapePreview
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Shape
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph.minimal.defaultNodeTheme
import org.jpablo.graphexplorer.viewer.models.Attributes
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.*
import org.jpablo.graphexplorer.viewer.widgets.Icons.*
import org.jpablo.graphexplorer.viewer.widgets.MenuEntry.{MenuOption, Sep}
import org.scalajs.dom
import org.scalajs.dom.svg.SVG

/** The single bar of chrome, zoned by frequency of use (see the top-bar design
  * study): navigation left · creation center · search/history/panels/settings
  * right. Occasional actions live inside menus or ⌘K, preferences behind the
  * gear — the bar itself carries only what gets hourly use.
  */
def Toolbar(projectName: Signal[String], commands: Commands, state: ViewerState): Div =
  import commands.{all, routerCmds, sections}

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

  // Application-level entries, not document actions: preferences, help, about,
  // source. One gear instead of five loose icons + a theme select in the bar.
  val gearMenu: Signal[Seq[MenuEntry[() => Unit]]] =
    Signal.fromValue(
      Seq(
        MenuOption("Preferences…", () => state.preferencesDialogOpen.set(true), Some("Theme and editing preferences"), None),
        MenuOption(
          all.helpKeyboardShortcuts.shortLabel,
          () => all.helpKeyboardShortcuts.execute(),
          Some(all.helpKeyboardShortcuts.description.getOrElse("")),
          all.helpKeyboardShortcuts.shortcut.map(_.toList)
        ),
        Sep,
        MenuOption("About Graph Explorer", () => all.openAboutDialog.execute(), None, None),
        MenuOption("Source on GitHub", () => { dom.window.open("https://github.com/jpablo/graph-explorer/tree/viewer", "_blank"); () }, None, None)
      )
    )

  div(
    idAttr := "toolbar",
    cls    := "navbar",
    // -------- Navigation --------
    div(
      cls := "navbar-start gap-4",
      IconToggle(
        "bi-layout-sidebar",
        "Toggle Library",
        state.leftPanelVisible,
        // Start-aligned: this sits at the window's left edge, where the default
        // centre-aligned bubble clips off-screen.
        tipPos = TooltipPos.bottomStart,
        mods = idAttr := "toggle-library"
      ),
      // -------- Breadcrumbs --------
      div(
        cls := "breadcrumbs text-md py-0",
        ul(
          li(
            a(cls := "text-sm opacity-60", "Library", onClick --> routerCmds.navigateHome.execute())
          ),
          li(
            cls := "text-sm",
            // The title itself is the rename affordance; the pencil is the hint.
            span(
              cls   := "cursor-pointer hover:underline decoration-dotted underline-offset-4",
              title := "Rename diagram",
              text <-- projectName,
              onClick --> all.changeProjectName.execute()
            ),
            IconButtonTitled("bi-pencil text-[.65rem]", "Change title")(all.changeProjectName.execute())
          )
        )
      )
    ),
    // -------- Creation --------
    div(
      cls := "navbar-center gap-2",
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
      Dropdown(
        title = span("Add"),
        options = filteredMenu(sections.add*),
        onClickHandler = _ --> (action => action())
      ),
      Dropdown(
        title = span("Select"),
        options = filteredMenu(sections.select*),
        onClickHandler = _ --> (action => action())
      ),
      // Actions absorbs the old "Copy as" menu: exporting IS an action, and two
      // top-level menus for it was one of the junk-drawer symptoms.
      Dropdown(
        title = span("Actions"),
        options = {
          val actionsWithExport: List[Command[?] | Sep.type] =
            sections.actions ++ (Sep :: sections.exportAs)
          filteredMenu(actionsWithExport*)
        },
        onClickHandler = _ --> (action => action())
      )
    ),
    // -------- Search · history · panels · settings --------
    div(
      cls := "navbar-end gap-2",
      CommandsPanel(state, commands).amend(cls := "hidden lg:block"),
      div(
        cls := "gx-tool-group",
        IconButton("bi-arrow-counterclockwise", all.undo.labelWithShortcut)(all.undo.execute()),
        IconButton("bi-arrow-clockwise", all.redo.labelWithShortcut)(all.redo.execute())
      ),
      PanelSectionToggles(state).amend(cls := "gx-tool-group"),
      Dropdown(
        title = emptyMod,
        options = gearMenu,
        onClickHandler = _ --> (action => action()),
        icon = i(cls := "bi bi-gear"),
        menuCls = "w-56"
      ).amend(cls := "dropdown-end")
    )
  )
