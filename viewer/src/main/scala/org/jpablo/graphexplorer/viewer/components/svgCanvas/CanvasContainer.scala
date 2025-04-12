package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.components.Commands
import org.jpablo.graphexplorer.viewer.components.svgCanvas.SvgCanvas.clientCoords
import org.jpablo.graphexplorer.viewer.state.ViewerState

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
  div(
    idAttr   := "canvas-container",
    tabIndex := 0,
    state.fitDiagram.events --> state.resetView(),
    child <-- state.finalSVG,
    focus <-- state.canvasContainerFocus.signal.changes,
    onKeyDown --> commands.handleKeyDown,
    onWheel(_.withCurrentValueOf(state.finalSVG)) --> { (e, svgElem) =>
      state.handleWheel(e, svgElem.ref.viewBox.baseVal)
    },
    onMouseDown.map(clientCoords) --> { (pos, shift) => state.selection.startSelectionArea(pos, shift) },
    // No Action is set when moving the mouse, to preserve the action set on mouse down
    onMouseMove.map(clientCoords) --> { (pos, shift) => state.selection.updateSelection(pos, shift) },
    onMouseUp --> state.selection.handleMouseUp
  )
