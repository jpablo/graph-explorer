package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
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
      title  := "Auto fit",
      cls("btn-active") <-- state.autoFit,
      "Auto",
      // daisyUI swap, class-driven (`swap-active`), so the two icons cross-fade
      // with a rotate instead of hard-swapping — and no checkbox nested inside
      // the button, which is what the input-driven swap idiom would require.
      span(
        cls := "swap swap-rotate",
        cls("swap-active") <-- state.autoFit,
        span(cls := "swap-on bi bi-check-circle"),
        span(cls := "swap-off bi bi-circle")
      ),
      onClick --> commands.all.autoFit.execute()
    ).tiny.ghost,
    Button(span().plusIcon, onClick --> commands.all.zoomIn.execute()).tiny.ghost
  )
