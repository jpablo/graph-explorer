package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.components.leftPanel.LeftPanel
import org.jpablo.graphexplorer.viewer.components.rightPanel.RightPanel
import org.jpablo.graphexplorer.viewer.components.svgCanvas.CanvasContainer
import org.jpablo.graphexplorer.viewer.state.ViewerState

/** The page of one diagram, with its own Library panel.
  *
  * An example uses this page. The diagram workspace (`AppRoutes`) uses the
  * same parts, but it keeps one Library panel for all its diagrams.
  */
def TopLevel(
    state:    ViewerState,
    router:   Router,
    commands: Commands,
    // Under the toolbar and above everything else: an ephemeral visit has to
    // announce itself before the reader invests edits in it.
    banner:   Option[Div] = None
): Div =
  TopLevelFrame(
    header = DiagramHeader(state, commands, banner),
    leftPanel = LeftPanel(state, router, commands),
    body = DiagramBody(state, router, commands)
  )

/** The layout of the diagram page. The header is above, and the Library panel
  * is at the left of the body.
  *
  * The parts are modifiers, so the workspace can give `children <-- ...` for
  * the parts that change with the diagram.
  */
def TopLevelFrame(
    header:    Modifier[HtmlElement],
    leftPanel: HtmlElement,
    body:      Modifier[HtmlElement]
): Div =
  div(
    idAttr := "top-level",
    header,
    div(
      cls := "flex flex-1 overflow-y-auto relative",
      leftPanel,
      body
    )
  )

/** One view of a diagram: its state and its commands.
  *
  * The diagram workspace gives the parts that it mounts one time (the toolbar,
  * the Library panel, the zoom bar) a signal of this value. A part reads the current view
  * from that signal.
  */
final case class DiagramView(state: ViewerState, commands: Commands)

/** The parts above the canvas row that belong to one diagram: the toolbar and
  * the banners.
  */
def DiagramHeader(state: ViewerState, commands: Commands, banner: Option[Div]): Seq[HtmlElement] =
  Toolbar(state.displayTitle, commands, state) +: DiagramBanners(state, banner)

/** The banners of one diagram, under the toolbar. The diagram workspace mounts
  * one toolbar for all its diagrams, and these banners for each diagram.
  */
def DiagramBanners(state: ViewerState, banner: Option[Div]): Seq[HtmlElement] =
  banner.toSeq ++
    // Under any banner, and above the canvas: §7.3 requires an answer before
    // the edit or the file is lost, so it must not be somewhere to scroll to.
    Seq(DocumentConflictBanner(state))

/** The parts of the canvas row of one view: all the row except the Library
  * panel. The zoom bar is between the canvas area and the right panel.
  */
def DiagramBody(state: ViewerState, router: Router, commands: Commands): Seq[HtmlElement] =
  val parts = DiagramParts(state, router, commands)
  parts.canvas +: ZoomToolbar(state, commands) +: parts.rest

/** The parts of the canvas row that belong to one diagram: all the row except
  * the Library panel and the zoom bar. The diagram workspace mounts one zoom
  * bar for all its diagrams, between `canvas` and `rest`.
  *
  * @param canvas the canvas area.
  * @param rest   the right panel and the dialogs.
  */
final case class DiagramParts(canvas: HtmlElement, rest: Seq[HtmlElement])

object DiagramParts:
  /** `canvasArea` goes on the canvas area. The workspace puts the unmount
    * callback of the view there, because the canvas area stays per diagram.
    */
  def apply(
      state:      ViewerState,
      router:     Router,
      commands:   Commands,
      canvasArea: Modifier[HtmlElement] = emptyMod
  ): DiagramParts =
    DiagramParts(
      // The context strip OVERLAYS the canvas (absolute, inside this wrapper)
      // instead of occupying a layout row: appearing must not shift the canvas —
      // the push-down/snap-back on every selection was genuinely annoying. Scoped
      // to the canvas area, so it never covers the library panel.
      canvas = div(
        cls := "relative flex flex-1 min-w-0",
        canvasArea,
        AttributesToolbar(state.displayTitle, commands, state),
        CanvasContainer(state, commands)
      ),
      rest = Seq(
        RightPanel(state),
        HelpDialog(state.helpDialogOpen, commands),
        AboutDialog(state.aboutDialogOpen),
        PreferencesDialog(state),
        RenameDiagramDialog(state),
        InfoAlert(state.infoBus),
        EditLabelDialog(state),
        EditTitleDialog(state),
        EditXLabelDialog(state),
        EditCellDialog(state),
        NewNodeLabelDialog(state),
        NewGroupLabelDialog(state),
        UnsavedChangesDialog(state, router),
        ErrorAlert(state.errorBus)
      )
    )
