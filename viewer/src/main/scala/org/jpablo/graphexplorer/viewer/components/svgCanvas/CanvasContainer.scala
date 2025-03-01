package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.components.Commands
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.state.ViewerState.handleWheel
import org.jpablo.graphexplorer.viewer.utils.Point2d

/** Creates a container div for the SVG canvas with mouse and keyboard interaction handlers
  *
  * @param state
  *   The viewer state containing diagram data and event handlers
  * @param fitDiagram
  *   Event stream that triggers fitting the diagram to the viewport
  * @return
  *   A div element containing the SVG canvas with interaction handlers
  */
def CanvasContainer(
    state:    ViewerState,
    commands: Commands
) =
  def clientCoords(e: dom.MouseEvent): (Point2d[Double], Boolean) = ((e.clientX, e.clientY), e.shiftKey)

  div(
    idAttr   := "canvas-container",
    tabIndex := 0,
    state.fitDiagram.events --> state.resetView(),
    child <-- state.finalSVG,
    onKeyDown --> commands.handleKeyDown,
    onWheel(_.withCurrentValueOf(state.finalSVG)) --> { (e, svgElem) =>
      handleWheel(state.zoomValue, state.translateXY)(e, svgElem.ref.viewBox.baseVal)
    },
    onMouseDown.map(clientCoords) --> { (pos, shift) => state.diagramSelection.startSelectionArea(pos, shift) },
    // No Action is set when moving the mouse, to preserve the action set on mouse down
    onMouseMove.map(clientCoords) --> { (pos, shift) => state.diagramSelection.updateSelection(pos, shift) },
    onMouseUp --> state.handleMouseUp
  )
