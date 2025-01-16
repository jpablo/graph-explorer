package org.jpablo.graphexplorer.viewer.components

import com.raquo.airstream.core.EventStream
import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.state.ViewerState


/** Creates a container div for the SVG canvas with mouse and keyboard interaction handlers
 * 
 * @param state The viewer state containing diagram data and event handlers
 * @param fitDiagram Event stream that triggers fitting the diagram to the viewport
 * @return A div element containing the SVG canvas with interaction handlers
 */
def CanvasContainer(
    state:      ViewerState,
    fitDiagram: EventStream[Unit]
) =
  import state.eventHandlers.updateTranslate

  def clientCoords(e: dom.MouseEvent): (Point2d[Double], Boolean) = ((e.clientX, e.clientY), e.shiftKey)

  div(
    idAttr   := "canvas-container",
    tabIndex := 0,
    fitDiagram --> state.resetView(),
    child <-- state.rawSVG.map(SvgCanvas(state)),
    onKeyDown --> state.handleKeyDown,
    onWheel.updateTranslate,
    onMouseDown.map(clientCoords) --> {(pos, shift) =>
      state.startSelection(pos, shift, Action.Selection)
    },
    // No Action is set when moving the mouse, to preserve the action set on mouse down
    onMouseMove.map(clientCoords) --> {(pos, shift) => state.updateSelection(pos, shift) },
    onMouseUp --> state.handleMouseUp,
  )
