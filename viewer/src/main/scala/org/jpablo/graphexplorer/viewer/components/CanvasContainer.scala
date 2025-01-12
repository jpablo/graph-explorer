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
    // --------------------------------
    onMouseDown.map(clientCoords).map((pos, shift) => MouseDown(pos, shift)) --> state.mouse.emitEvent,
    onMouseMove.map(clientCoords).map((pos, shift) => MouseMove(pos, shift)) --> state.mouse.emitEvent,
    onMouseUp.map(clientCoords).map((pos, shift) => MouseUp(pos, shift)) --> state.mouse.emitEvent,
    // --------------------------------

  )

// def findSelectableElement(event: dom.MouseEvent): Option[(NodeId | Arrow, Boolean)] =
//   dom.console.log("-------- findSelectableElement --------")
//   val elements = dom.document.elementsFromPoint(event.clientX, event.clientY)

//   // Filter SVG elements and include their ancestor nodes
//   val svgElements = elements.toArray
//     .filter(_.namespaceURI == "http://www.w3.org/2000/svg")
//     .flatMap(element => Option(element.closest("g.node, g.edge")))
//     .distinct // Remove duplicates

//   svgElements
//     .map(SelectableElement.fromDomElement)
//     .collectFirst { case Some(g) => g }
//     .flatMap:
//       case n: NodeElement => Some(n.nodeId)
//       case e: EdgeElement => e.toArrow
//     .map((_, event.metaKey))
