package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.components.Commands
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.utils.ClientPoint

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
  div(
    idAttr   := "canvas-container",
    tabIndex := 0,
    state.fitDiagram.events --> state.resetView(),
    // the main canvas!!
    child.maybe <-- state.finalSVG,
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
    onWheel.preventDefault(_.withCurrentValueOf(state.finalSVG)) --> ((e, svgElemO) =>
      svgElemO.map(s => state.handleWheel(e, s.ref.viewBox.baseVal))
    )
  )

extension (e: dom.MouseEvent)
  def clientCoords    = (ClientPoint(e.clientX, e.clientY), e.shiftKey)
  def leftButton      = e.button == 0
  def leftButtonMoved = e.buttons == 1
