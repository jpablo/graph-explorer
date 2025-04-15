package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.components.Commands
import org.jpablo.graphexplorer.viewer.state.mouseActions.MouseAction.*
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.utils.{ClientPoint, UserActionRect}

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
    child <-- state.finalSVG,
    // we need a way to move the focus here after certain events
    focus <-- state.canvasContainerFocus.signal.changes,
    // abort ongoing mouse actions when the focus is lost
    onBlur --> state.mouseAction.inactive(),
    onKeyDown --> commands.handleKeyDown,
    onWheel(_.withCurrentValueOf(state.finalSVG)) --> ((e, svgElem) => state.handleWheel(e, svgElem.ref.viewBox.baseVal)),
    // mouse related "actions"
    // 1. Drawing a selecting rectangle starts here. Other actions start in their respective elements.
    onMouseDown.filter(leftButton).map(clientCoords) --> { (pos, shift) =>
      state.mouseAction.start(ExtendSelectionAction(UserActionRect(pos, pos, shift)))
    },
    // 2. Any ongoing action is updated here (i.e. mouse position)
    onMouseMove.filter(leftButtonMoved).map(clientCoords) --> state.mouseAction.updateEndpoint.tupled,
    // 3. Any ongoing action end here
    onMouseUp.filter(leftButton) --> { ev =>
      state.mouseAction.now() match
        case a: AddNewArrowAction     => state.handleAddNewArrowMouseUp(ev, a)
        case a: MoveArrowSourceAction => state.handleMoveArrowStartMouseUp(ev, a)
        case a: ExtendSelectionAction => ()
        case Inactive                 => ()
      state.mouseAction.inactive()
    }
  )

extension (e: dom.MouseEvent)
  def clientCoords    = (ClientPoint(e.clientX, e.clientY), e.shiftKey)
  def leftButton      = e.button == 0
  def leftButtonMoved = e.buttons == 1
