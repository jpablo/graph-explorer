package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import com.raquo.laminar.nodes.ChildNode
import org.jpablo.graphexplorer.viewer.components.Commands
import org.jpablo.graphexplorer.viewer.components.scene3d.Scene3D
import org.jpablo.graphexplorer.viewer.state.{EditorNotice, ViewerState}
import org.jpablo.graphexplorer.viewer.utils.ClientPoint
import org.jpablo.graphexplorer.viewer.widgets.{Tooltip, TooltipPos}

import scala.scalajs.js

/** Creates a container div for the SVG canvas with mouse and keyboard interaction handlers
  *
  * @param state
  *   The viewer state containing diagram data and event handlers
  * @param fitDiagram
  *   Event stream that triggers fitting the diagram to the viewport
  * @return
  *   A div element containing the SVG canvas with interaction handlers
  */
def CanvasContainer(state: ViewerState, commands: Commands) =
  // A press on the inert canvas of a view-only diagram pulses the chip: the
  // answer to "why won't it select?" arrives at the moment the question is asked.
  val viewOnlyNudge = EventBus[Unit]()
  div(
    idAttr   := "canvas-container",
    tabIndex := 0,
    state.fitDiagram.events --> state.resetView(),
    // the main canvas!! Either the engine's SVG or the experimental three.js
    // scene — a fresh Scene3D per toggle-on, so 3D state never outlives the
    // mode. view3DActive, not view3D: disabling the 3D feature in Preferences
    // drops straight back to the SVG even if the mode Var still says 3D.
    child.maybe <-- state.view3DActive.distinct.flatMapSwitch:
      case true  => Signal.fromValue(Some(Scene3D(state)): Option[ChildNode.Base])
      case false => state.finalSVG.map(svg => svg: Option[ChildNode.Base])
    ,
    // Render-only diagram kinds (Mermaid beyond flowcharts): the notice used to
    // live only inside the sources panel, invisible unless that panel was open.
    // The chip sits on the canvas itself — the place where the limitation bites.
    child.maybe <-- state.editorNotice.signal
      .map(_.filter(n => !n.isError))
      .map(_.map(ViewOnlyChip(_, viewOnlyNudge.events))),
    onMouseDown --> { _ =>
      if state.editorNotice.now().exists(n => !n.isError) then viewOnlyNudge.emit(())
    },
    // we need a way to move the focus here after certain events
    focus <-- state.canvasContainerFocus,
    // abort ongoing mouse actions when the focus is lost
    // TODO: this makes the "ArrowFromSourceToPointer" disappear when the focus is lost.
    // Perhaps the solution is to make sure the focus is not lost when clicking on the arrow?
//    onBlur --> state.mouseAction.inactive(),
    onKeyDown --> commands.handleKeyDown,
    // The canvas CONSUMES the wheel gesture (it pans/zooms a transform, the page
    // never scrolls), so it must preventDefault — without it the browser sees an
    // unconsumed horizontal scroll chaining to an unscrollable viewport, and
    // macOS classifies the leftward overscroll as the history back-swipe.
    // Also keeps trackpad pinch (wheel + ctrlKey) from zooming the whole page.
    // preventDefault works here because the div's listener is non-passive —
    // Chrome's passive-by-default applies only to window/document/body.
    // In 3D, the scene owns the wheel gesture; the guard keeps the 2D
    // transform from silently mutating under an unmounted SVG. preventDefault
    // still applies (it runs at the listener, before the filter), so the
    // back-swipe protection above holds in both modes.
    onWheel.preventDefault(_.filter(_ => !state.view3DActiveNow).withCurrentValueOf(state.finalSVG)) --> (
      (e, svgElemO) => svgElemO.map(s => state.handleWheel(e, s.ref.viewBox.baseVal))
    )
  )

/** The persistent "this diagram is view-only" pill, floating top-center on the
  * canvas. The full explanation lives in its tooltip; a nudge (a click on the
  * inert canvas) plays a small scale pulse to draw the eye.
  */
private def ViewOnlyChip(notice: EditorNotice, nudge: EventStream[Unit]) =
  div(
    idAttr := "view-only-chip",
    cls    := "floating-toolbar",
    Tooltip(
      text = notice.message,
      cls := TooltipPos.bottom,
      span(cls := "flex items-center gap-1.5", i(cls := "bi bi-eye"), "View-only")
    ),
    inContext { el =>
      nudge --> { _ =>
        if !dom.window.matchMedia("(prefers-reduced-motion: reduce)").matches then
          el.ref
            .asInstanceOf[js.Dynamic]
            .animate(
              js.Array(
                js.Dynamic.literal(transform = "translateX(-50%) scale(1)"),
                js.Dynamic.literal(transform = "translateX(-50%) scale(1.15)"),
                js.Dynamic.literal(transform = "translateX(-50%) scale(1)")
              ),
              js.Dynamic.literal(duration = 350, easing = "ease-out")
            )
      }
    }
  )

extension (e: dom.MouseEvent)
  def clientCoords    = (ClientPoint(e.clientX, e.clientY), e.shiftKey)
  def leftButton      = e.button == 0
  def leftButtonMoved = e.buttons == 1
