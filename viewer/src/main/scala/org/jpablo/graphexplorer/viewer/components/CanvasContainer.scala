package org.jpablo.graphexplorer.viewer.components

import com.raquo.airstream.core.EventStream
import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.state.MouseInteraction.CanvasMouseEvent.*


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
    onMouseDown.map(clientCoords) --> {(pos, shift) => state.mouse.emitEvent(MouseDown(pos, shift, Action.Selection)) },
    onMouseMove.map(clientCoords) --> {(pos, shift) => state.mouse.emitEvent(MouseMove(pos, shift)) },
    onMouseUp --> state.handleMouseUp,
  )
