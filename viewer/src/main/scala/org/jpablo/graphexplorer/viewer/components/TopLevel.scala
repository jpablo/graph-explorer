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
    // Under any banner, and above the canvas: §7.3 requires an answer before
    // the edit or the file is lost, so it must not be somewhere to scroll to.
    DocumentConflictBanner(state),
    // The record's counterpart to the strip above: one reports a loose file
    // that moved under an edit, the other a bound record that diverged from
    // its origin. Both are §7.3/§8 refusing to resolve a disagreement silently.
    OriginStateBanner(state),
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
      UnsavedChangesDialog(state, router),
      ErrorAlert(state.errorBus)
    )
  )
