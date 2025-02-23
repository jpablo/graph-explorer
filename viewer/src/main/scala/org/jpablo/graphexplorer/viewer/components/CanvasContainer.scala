package org.jpablo.graphexplorer.viewer.components

import com.raquo.airstream.core.EventStream
import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.jpablo.graphexplorer.viewer.state.ViewerState

/** Creates a container div for the SVG canvas with mouse and keyboard interaction handlers
  *
  * @param state
  *   The viewer state containing diagram data and event handlers
  * @param fitDiagram
  *   Event stream that triggers fitting the diagram to the viewport
  * @param selectionSidebar
  *   The selection sidebar component to be rendered inside the canvas container
  * @return
  *   A div element containing the SVG canvas with interaction handlers
  */
def CanvasContainer(
    state:           ViewerState,
    fitDiagram:      EventStream[Unit],
    selectionSidebar: ReactiveHtmlElement[dom.HTMLDivElement]
) =
  import state.eventHandlers.updateTranslate

  def clientCoords(e: dom.MouseEvent): (Point2d[Double], Boolean) = ((e.clientX, e.clientY), e.shiftKey)

  div(
    idAttr   := "canvas-container",
    tabIndex := 0,
    styleAttr <-- state.leftPanelVisible.signal.map(visible => 
      if visible then "--selection-sidebar-left: .5rem;"
      else "--selection-sidebar-left: 2.75rem;"
    ),
    fitDiagram --> state.resetView(),
    child <-- state.rawSVG.map(SvgCanvas(state)),
    selectionSidebar,
    onKeyDown --> state.handleKeyDown,
    onWheel.updateTranslate,
    onMouseDown.map(clientCoords) --> { (pos, shift) => state.startSelectionArea(pos, shift) },
    // No Action is set when moving the mouse, to preserve the action set on mouse down
    onMouseMove.map(clientCoords) --> { (pos, shift) => state.updateSelection(pos, shift) },
    onMouseUp --> state.handleMouseUp
  )
