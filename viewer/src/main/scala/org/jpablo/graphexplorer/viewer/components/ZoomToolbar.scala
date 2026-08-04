package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.widgets.{SwapIcon, activeWhen}
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.layout3d.Layout3D
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
    Button(span().plusIcon, onClick --> commands.all.zoomIn.execute()).tiny.ghost,
    // ---------- render mode ----------
    // The whole 3D cluster exists only once the feature is enabled in
    // Preferences — an experiment shouldn't occupy toolbar space for
    // everyone else.
    child.maybe <-- state.enable3D.signal.map(en => Option.when(en)(threeDToggle(state))),
    // The layout picker and navigation toggle only mean something while the
    // 3D scene is up. Switching layouts morphs the drawing live.
    child.maybe <-- state.view3DActive.map(on => Option.when(on)(threeDControls(state)))
  )

private def threeDToggle(state: ViewerState) =
  Button(
    title := "3D view (experimental)",
    "3D",
    SwapIcon(state.view3D.signal, onIcon = "bi bi-check-circle", offIcon = "bi bi-circle"),
    onClick --> state.view3D.update(!_)
  ).tiny.ghost.activeWhen(state.view3D.signal)

private def threeDControls(state: ViewerState) =
  div(
    cls := "flex items-center gap-1",
    layout3DSelect(state),
    Button(
      title := "Face the drawing straight-on (orthogonal to its plane)",
      i(cls := "bi bi-aspect-ratio"),
      onClick --> state.face3DFront.emit(())
    ).tiny.ghost,
    IconToggle(
      "bi-arrow-repeat",
      "Trackpad navigation: scroll orbits without clicking, pinch zooms (off: wheel zooms). ⌥ pans in both modes.",
      state.nav3DTrackpad
    )
  )

private def layout3DSelect(state: ViewerState) =
  SelectBox(
    SelectVariant.ghost,
    SelectVariant.xs,
    cls   := "w-auto",
    title := "3D layout algorithm",
    Layout3D.all.map(algo => option(value := algo.id, algo.label)),
    controlled(
      value <-- state.layout3D.signal,
      onChange.mapToValue --> state.layout3D.writer
    )
  )
