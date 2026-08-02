package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.components.leftPanel.LeftPanel
import org.jpablo.graphexplorer.viewer.components.rightPanel.RightPanel
import org.jpablo.graphexplorer.viewer.components.svgCanvas.CanvasContainer
import org.jpablo.graphexplorer.viewer.state.ViewerState

def TopLevel(
    state:    ViewerState,
    router:   Router,
    commands: Commands,
    // Under the toolbar and above everything else: an ephemeral visit has to
    // announce itself before the reader invests edits in it.
    banner:   Option[Div] = None
): Div =
  div(
    idAttr := "top-level",
    Toolbar(state.displayTitle, commands, state),
    banner.toSeq,
    div(
      cls := "flex flex-1 overflow-y-auto relative",
      LeftPanel(state, router, commands),
      // The context strip OVERLAYS the canvas (absolute, inside this wrapper)
      // instead of occupying a layout row: appearing must not shift the canvas —
      // the push-down/snap-back on every selection was genuinely annoying. Scoped
      // to the canvas area, so it never covers the library panel.
      div(
        cls := "relative flex flex-1 min-w-0",
        AttributesToolbar(state.displayTitle, commands, state),
        CanvasContainer(state, commands)
      ),
      ZoomToolbar(state, commands),
      RightPanel(state),
      HelpDialog(state.helpDialogOpen, commands),
      AboutDialog(state.aboutDialogOpen),
      PreferencesDialog(state),
      RenameProjectDialog(state),
      InfoAlert(state.infoBus),
      EditLabelDialog(state),
      EditCellDialog(state),
      NewNodeLabelDialog(state),
      NewGroupLabelDialog(state),
      ErrorAlert(state.errorBus)
    )
  )
