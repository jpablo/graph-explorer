package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.components.attributes.previews.ShapePreview
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Shape
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph.minimal.defaultNodeTheme
import org.jpablo.graphexplorer.viewer.models.Attributes
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.utils.Platform
import org.jpablo.graphexplorer.viewer.widgets.*
import org.jpablo.graphexplorer.viewer.widgets.Icons.*
import org.jpablo.graphexplorer.viewer.widgets.MenuEntry.{MenuOption, Sep}
import org.scalajs.dom
import org.scalajs.dom.svg.SVG

/** The toolbar of one view. An example uses it, and a test.
  *
  * @param diagramName the title to show. A caller can give a fixed title.
  */
def Toolbar(
    diagramName: Signal[String],
    commands:    Commands,
    state:       ViewerState,
    isMac:       Boolean = Platform.isMac
): Div =
  Toolbar(Val(DiagramView(state, commands)), diagramName, state.leftPanelVisible, isMac)

/** The single bar of chrome, zoned by frequency of use (see the top-bar design
  * study): navigation left · creation center · search/history/panels/settings
  * right. Occasional actions live inside menus or ⌘K, preferences behind the
  * gear — the bar itself carries only what gets hourly use.
  *
  * The diagram workspace mounts this bar one time, and gives it the views one
  * after the other in `views`. Thus a switch of diagrams keeps the DOM nodes of
  * the bar.
  *
  * The labels, the tooltips and the shortcuts are the same for each view. The
  * menus and the title come from the current view through `flatMapSwitch`. A
  * click reads the view at the time of the click, not at construction.
  *
  * @param leftPanelVisible the visibility of the Library panel. The workspace
  *                         gives its own Var, because the value outlives each
  *                         view.
  */
def Toolbar(
    views:            Signal[DiagramView],
    diagramName:      Signal[String],
    leftPanelVisible: Var[Boolean],
    isMac:            Boolean
): Div =

  /** An action on the view at the time of the event. */
  type ViewAction = DiagramView => Unit

  /** A menu handler that runs the action of the option on the current view. */
  def onCurrentView(ep: EventProcessor[dom.MouseEvent, ViewAction]): Modifier[Anchor] =
    ep.compose(_.withCurrentValueOf(views)) --> ((action, view) => action(view))

  /** A tooltip that reads its text from the current view. The text is the
    * same for each view, so `distinct` keeps the DOM as it is.
    */
  def viewTip(tip: DiagramView => String): Signal[String] = views.map(tip).distinct

  /** An icon button whose tooltip and action come from the current view. */
  def viewIconButton(icon: String, tip: DiagramView => String)(action: ViewAction): Div =
    val text = viewTip(tip)
    Tooltip(
      text,
      cls := TooltipPos.bottom,
      button(
        typ := "button",
        cls := "gx-icon-btn",
        aria.label <-- text,
        i(cls := icon),
        onClick(_.sample(views)) --> action
      )
    )

  def shapePreview(shape: Shape): ReactiveSvgElement[SVG] | String =
    ShapePreview(shape, 16).map(_()).getOrElse(shape.toString)

  val defaultShapePreview: ReactiveSvgElement[SVG] | String =
    shapePreview((defaultNodeTheme.getAs(Shape): Shape))

  // The options do not change with the view. A click reads the view.
  val shapePreviews: Seq[MenuEntry[ViewAction]] =
    Seq(Shape.box, Shape.circle, Shape.ellipse, Shape.diamond, Shape.star)
      .map: shape =>
        MenuOption(
          elem = shapePreview(shape),
          value = (view: DiagramView) => view.state.createNodeMaybePrompt(Attributes.of(Shape -> shape)),
          description = None,
          shortcut = None
        )

  /** The options of a menu for the current view and its selection. The view
    * comes through one `flatMapSwitch`, so the options never mix the commands
    * of one view with the selection of a different view. A switch renders the
    * options again, inside the dropdown. The dropdown button stays.
    */
  def filteredMenu(cmds: Commands => Seq[Command[?] | Sep.type]): Signal[Seq[MenuEntry[ViewAction]]] =
    views.flatMapSwitch: view =>
      view.state.selection.signal.map: selection =>
        cmds(view.commands)
          .filter:
            case cmd: Command[?] => cmd.isVisible(selection)
            case _               => true
          .map:
            case cmd: Command[?] =>
              MenuOption(
                elem = cmd.shortLabel,
                value = (_: DiagramView) => cmd.execute(),
                description = Some(cmd.description.getOrElse(cmd.shortLabel)),
                shortcut = cmd.shortcut.map(_.toList)
              )
            case _ => Sep

  // Application-level entries, not document actions: preferences, help, about,
  // source. One gear instead of five loose icons + a theme select in the bar.
  // The labels are the same for each view, so `distinct` keeps the options.
  val gearMenu: Signal[Seq[MenuEntry[ViewAction]]] =
    views
      .map: view =>
        val help = view.commands.all.helpKeyboardShortcuts
        (help.shortLabel, help.description.getOrElse(""), help.shortcut.map(_.toList))
      .distinct
      .map: (helpLabel, helpDescription, helpShortcut) =>
        Seq(
          MenuOption(
            "Preferences…",
            (view: DiagramView) => view.state.preferencesDialogOpen.set(true),
            Some("Theme and editing preferences"),
            None
          ),
          MenuOption(
            helpLabel,
            (view: DiagramView) => view.commands.all.helpKeyboardShortcuts.execute(),
            Some(helpDescription),
            helpShortcut
          ),
          Sep,
          MenuOption("About Graph Explorer", (view: DiagramView) => view.commands.all.openAboutDialog.execute(), None, None),
          MenuOption(
            "Source on GitHub",
            (_: DiagramView) => { dom.window.open("https://github.com/jpablo/graph-explorer/tree/viewer", "_blank"); () },
            None,
            None
          )
        )

  div(
    idAttr := "toolbar",
    cls    := "navbar",
    // The command key of the platform + B shows or hides the Library: ⌘B on a
    // Mac, Ctrl+B on Windows and Linux (see Platform.commandKey). The listener
    // is on the document, so the key also works when the source editor or the
    // Library has the focus. A key with Shift or Alt is not this key. The bar
    // mounts one time for all the views, so the page has one listener.
    documentEvents(_.onKeyDown).filter(e =>
      e.key.toLowerCase == "b" && !e.shiftKey && !e.altKey && Platform.commandKey(e.metaKey, e.ctrlKey, isMac)
    ) --> { e =>
      e.preventDefault()
      leftPanelVisible.update(!_)
    },
    // -------- Navigation --------
    div(
      cls := "navbar-start gap-4",
      IconToggle(
        "bi-layout-sidebar",
        if isMac then "Toggle Library (⌘B)" else "Toggle Library (Ctrl+B)",
        leftPanelVisible,
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
            a(
              cls := "text-sm opacity-60",
              "Library",
              onClick(_.sample(views)) --> (_.commands.routerCmds.navigateHome.execute())
            )
          ),
          li(
            cls := "text-sm",
            // The title itself is the rename affordance; the pencil is the hint.
            span(
              cls   := "cursor-pointer hover:underline decoration-dotted underline-offset-4",
              title := "Rename diagram",
              text <-- diagramName,
              onClick(_.sample(views)) --> (_.commands.all.renameDiagram.execute())
            ),
            IconButtonTitled(
              "bi-pencil text-[.65rem]",
              "Change title",
              onClick(_.sample(views)) --> (_.commands.all.renameDiagram.execute())
            )(())
          )
        )
      )
    ),
    // -------- Creation --------
    div(
      cls := "navbar-center gap-2",
      div(
        cls := "flex-nowrap",
        Tooltip(
          viewTip(_.commands.all.newNode.labelWithShortcut),
          cls := TooltipPos.bottom,
          Button(
            cls := "gap-1 pl-1",
            i(cls := "bi bi-plus"),
            defaultShapePreview match
              case svg: ReactiveSvgElement[SVG] => span(svg)
              case str: String                  => span(str)
            ,
            onClick(_.sample(views)) --> (_.commands.all.newNode.execute())
          ).tiny
        ),
        // --- extra options ---
        Dropdown(
          title = emptyMod,
          options = Signal.fromValue(shapePreviews),
          onClickHandler = onCurrentView,
          icon = i().threeDotsVertical,
          join = true,
          menuCls = "items-center"
        ).amend(cls := "dropdown-center ml-[-1px]")
      ),
      Dropdown(
        title = span("Add"),
        options = filteredMenu(_.sections.add),
        onClickHandler = onCurrentView
      ),
      Dropdown(
        title = span("Select"),
        options = filteredMenu(_.sections.select),
        onClickHandler = onCurrentView
      ),
      // Actions absorbs the old "Copy as" menu: exporting IS an action, and two
      // top-level menus for it was one of the junk-drawer symptoms. Import sits
      // in the same menu for the same reason, in its own group ABOVE export —
      // it is the mirror of it, and the pair reads as the document's two ends.
      // Not in "Add", which is additive: this replaces the whole document.
      Dropdown(
        title = span("Actions"),
        options = filteredMenu: commands =>
          import commands.sections
          // Built with List.concat rather than chained `++`: inferring across two
          // `Sep :: …` prepends widens the separator to MenuEntry[Nothing] and
          // the union collapses. One expected type, applied to every part.
          val importGroup: List[Command[?] | Sep.type] = Sep :: sections.importFrom
          val exportGroup: List[Command[?] | Sep.type] = Sep :: sections.exportAs
          List.concat(sections.actions, importGroup, exportGroup)
        ,
        onClickHandler = onCurrentView
      )
    ),
    // -------- Search · history · panels · settings --------
    div(
      cls := "navbar-end gap-2",
      CommandsPanel(views, isMac).amend(cls := "hidden lg:block"),
      div(
        cls := "gx-tool-group",
        viewIconButton("bi-arrow-counterclockwise", _.commands.all.undo.labelWithShortcut)(_.commands.all.undo.execute()),
        viewIconButton("bi-arrow-clockwise", _.commands.all.redo.labelWithShortcut)(_.commands.all.redo.execute())
      ),
      PanelSectionToggles(views.map(_.state)).amend(cls := "gx-tool-group"),
      Dropdown(
        // Glyph-only trigger: names the button for screen readers, which
        // otherwise get "button". Matches the library navbar's gear.
        title = aria.label := "Settings",
        options = gearMenu,
        onClickHandler = onCurrentView,
        icon = i(cls := "bi bi-gear"),
        menuCls = "w-56"
      ).amend(cls := "dropdown-end")
    )
  )
