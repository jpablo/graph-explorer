package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.backends.Renderer
import org.jpablo.graphexplorer.viewer.widgets.{SwapIcon, activeWhen}
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.layout3d.Layout3D
import org.jpablo.graphexplorer.viewer.state.{Settings3D, ViewerState}
import org.jpablo.graphexplorer.viewer.widgets.*
import org.jpablo.graphexplorer.viewer.widgets.Icons.*

/** The zoom bar of one view. An example uses it, and a test. */
def ZoomToolbar(state: ViewerState, commands: Commands): Div =
  ZoomToolbar(Val(DiagramView(state, commands)), state.settings3D, state.rendererOption.available)

/** The zoom bar: the zoom buttons, Auto fit, the renderer, and the 3D controls.
  *
  * The diagram workspace mounts this bar one time, and gives it the views one
  * after the other in `views`. Thus a switch of diagrams keeps the DOM nodes of
  * the bar.
  *
  * A value that changes with the view (Auto fit, the renderer) comes from the
  * current view through `flatMapSwitch`. A click reads the view at the time of
  * the click, not at construction.
  *
  * @param settings3D the 3D settings. The workspace gives its own value,
  *                   because the values outlive each view.
  * @param renderers  the renderers of the select. Each view has the same list.
  */
def ZoomToolbar(views: Signal[DiagramView], settings3D: Settings3D, renderers: List[Renderer]): Div =
  // A new view starts with Auto on. `distinct` keeps the button as it is when
  // the value of the new view is the same.
  val autoFit = views.flatMapSwitch(_.state.autoFit.signal).distinct
  div(
    idAttr := "zoom-toolbar",
    cls    := "floating-toolbar",
    // ---------- zoom ----------
    Button(span().dashIcon, onClick(_.sample(views)) --> (_.commands.all.zoomOut.execute())).tiny.ghost,
    Button(
      // The label is the same for each view, so `distinct` keeps the DOM as it is.
      text <-- views.map(_.commands.all.fit.shortLabel).distinct,
      onClick(_.sample(views)) --> (_.commands.all.fit.execute())
    ).tiny.ghost,
    Button(
      title := "Auto fit",
      "Auto",
      SwapIcon(autoFit, onIcon = "bi bi-check-circle", offIcon = "bi bi-circle"),
      onClick(_.sample(views)) --> (_.commands.all.autoFit.execute())
    ).tiny.ghost.activeWhen(autoFit),
    Button(span().plusIcon, onClick(_.sample(views)) --> (_.commands.all.zoomIn.execute())).tiny.ghost,
    // ---------- render mode ----------
    // The 3D scene draws the graph itself, so the renderer has no effect there.
    // `distinct`: a value that does not change keeps the child as it is.
    child.maybe <-- settings3D.active.distinct.map(on => Option.when(!on)(rendererSelect(views, renderers))),
    // The whole 3D cluster exists only once the feature is enabled in
    // Preferences — an experiment shouldn't occupy toolbar space for
    // everyone else.
    child.maybe <-- settings3D.enable3D.signal.distinct.map(en => Option.when(en)(threeDToggle(settings3D))),
    // The layout picker and navigation toggle only mean something while the
    // 3D scene is up. Switching layouts morphs the drawing live.
    child.maybe <-- settings3D.active.distinct.map(on => Option.when(on)(threeDControls(views, settings3D)))
  )

/** The renderer of this diagram (ADR 0002). Auto is the renderer of the language of the source.
  *
  * The value and the label of Auto come from the current view. A change goes
  * to the view at the time of the change.
  */
private def rendererSelect(views: Signal[DiagramView], renderers: List[Renderer]) =
  // `controlled` needs a plain listener. Thus the listener sends the value to
  // this bus, and a second binder gives it to the current view.
  val picks = EventBus[String]()
  SelectBox(
    SelectVariant.ghost,
    SelectVariant.xs,
    idAttr := "renderer-select",
    cls    := "w-auto",
    title  := "Renderer",
    option(value := ViewerState.autoRenderer, text <-- views.flatMapSwitch(_.state.rendererOption.autoLabel)),
    renderers.map(renderer => option(value := renderer.id, renderer.label)),
    controlled(
      value <-- views.flatMapSwitch(_.state.rendererOption.selected),
      onChange.mapToValue --> picks.writer
    ),
    picks.events.withCurrentValueOf(views) --> ((pick, view) => view.state.rendererOption.select(pick))
  )

private def threeDToggle(settings3D: Settings3D) =
  Button(
    title := "3D view (experimental)",
    "3D",
    SwapIcon(settings3D.view3D.signal, onIcon = "bi bi-check-circle", offIcon = "bi bi-circle"),
    onClick --> settings3D.view3D.update(!_)
  ).tiny.ghost.activeWhen(settings3D.view3D.signal)

private def threeDControls(views: Signal[DiagramView], settings3D: Settings3D) =
  div(
    cls := "flex items-center gap-1",
    layout3DSelect(settings3D),
    Button(
      title := "Face the drawing straight-on (orthogonal to its plane)",
      i(cls := "bi bi-aspect-ratio"),
      onClick(_.sample(views)) --> (_.state.face3DFront.emit(()))
    ).tiny.ghost,
    // TooltipPos.top: this toolbar is docked at the canvas's BOTTOM edge, so a
    // bottom-opening bubble falls off the viewport.
    IconToggle(
      "bi-arrow-repeat",
      "Trackpad navigation: scroll orbits without clicking, pinch zooms (off: wheel zooms). ⌥ pans in both modes.",
      settings3D.nav3DTrackpad,
      tipPos = TooltipPos.top
    ),
    IconToggle(
      "bi-person-video",
      "Labels face the camera (off: labels are fixed sheets facing the front view)",
      settings3D.label3DBillboard,
      tipPos = TooltipPos.top
    )
  )

private def layout3DSelect(settings3D: Settings3D) =
  SelectBox(
    SelectVariant.ghost,
    SelectVariant.xs,
    cls   := "w-auto",
    title := "3D layout algorithm",
    Layout3D.all.map(algo => option(value := algo.id, algo.label)),
    controlled(
      value <-- settings3D.layout3D.signal,
      onChange.mapToValue --> settings3D.layout3D.writer
    )
  )
