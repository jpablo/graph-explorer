package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.components.Commands
import org.jpablo.graphexplorer.viewer.components.svgCanvas.SvgCanvas.{clientCoords, leftButton, leftButtonMoved}
import org.jpablo.graphexplorer.viewer.state.MouseAction.*
import org.jpablo.graphexplorer.viewer.state.{MouseAction, ViewerState}

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
    onBlur --> state.selection.mouseAction.inactive(),
    onKeyDown --> commands.handleKeyDown,
    onWheel(_.withCurrentValueOf(state.finalSVG)) --> { (e, svgElem) =>
      state.handleWheel(e, svgElem.ref.viewBox.baseVal)
    },
    onMouseDown.filter(leftButton).map(clientCoords) --> state.selection.mouseAction.startExtendSelection.tupled,
    onMouseMove.filter(leftButtonMoved).map(clientCoords) --> state.selection.mouseAction.updateEndpoint.tupled,
    onMouseUp.filter(leftButton) --> { ev =>
      val mouseAction = state.selection.mouseAction.now()
      state.selection.mouseAction.inactive()
      mouseAction match
        case a: AddNewArrowAction     => state.selection.handleAddNewArrowEnd(ev, a)
        case a: ExtendSelectionAction => ()
        case a: MoveArrowStartAction  => ()
        case Inactive                 => ()
    }
  )
