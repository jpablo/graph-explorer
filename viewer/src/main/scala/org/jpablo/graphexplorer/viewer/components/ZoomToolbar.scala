package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.widgets.{SwapIcon, activeWhen}
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.*
import org.jpablo.graphexplorer.viewer.widgets.Icons.*

def ZoomToolbar(state: ViewerState, commands: Commands) =

  div(
    idAttr := "zoom-toolbar",
    cls    := "floating-toolbar",
    // ---------- zoom ----------
    Button(span().dashIcon, onClick --> commands.all.zoomOut.execute()).tiny.ghost,
    Button(commands.all.fit.shortLabel, onClick --> commands.all.fit.execute()).tiny.ghost,
    Button(
      title := "Auto fit",
      "Auto",
      SwapIcon(state.autoFit.signal, onIcon = "bi bi-check-circle", offIcon = "bi bi-circle"),
      onClick --> commands.all.autoFit.execute()
    ).tiny.ghost.activeWhen(state.autoFit.signal),
    Button(span().plusIcon, onClick --> commands.all.zoomIn.execute()).tiny.ghost
  )
