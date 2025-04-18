package org.jpablo.graphexplorer.viewer.state.mouseActions

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.components.selection.EdgeElement
import org.jpablo.graphexplorer.viewer.components.svgCanvas.{ArrowEndpointControl, clientCoords}
import org.jpablo.graphexplorer.viewer.domUtils.elementsFromPoint
import org.jpablo.graphexplorer.viewer.models.{Arrow, ArrowEndpointId, NodeId}
import org.jpablo.graphexplorer.viewer.state.DiagramSelectionOps.findClosestElementId
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.state.mouseActions.MouseAction.MoveArrowEndpointAction
import org.jpablo.graphexplorer.viewer.utils.MouseActionRect

/*
 * This trait contains the logic for handling mouse actions related to moving the start of an arrow in the graph.
 * It includes methods for handling mouse events, updating the arrow's position, and rendering the arrow.
 */
trait MoveArrowEndpointOps:
  this: ViewerState =>

  // 1. Create the UI control
  def buildArrowEndpointControl(originator: EdgeElement, endpoint: ArrowEndpoint) =
    ArrowEndpointControl(
      originator,
      endpoint,
      onMouseDown.stopPropagation.map(clientCoords) --> { (pos, _) =>
        mouseAction.start(MoveArrowEndpointAction(MouseActionRect(start = pos, end = pos, shift = false), originator, endpoint))
      },
      onMouseUp.stopPropagation --> mouseAction.inactive()
    )

  // 2. Draw a dynamic arrow that follows the pointer
  // see: [[org.jpablo.graphexplorer.viewer.components.svgCanvas.ArrowBetweenPointerAndEndpoint]]


  // 3. Update the selection as the pointer is moving
  def onMoveArrowSourceAction(action: MouseAction.MoveArrowEndpointAction) =
    val start     = action.originator
    val neighbors = dom.document.elementsFromPoint(action.rect.end.x, action.rect.end.y)

    findClosestElementId(neighbors, "g.node") match
      case Some(endElementId) =>
        val ignore = (start, endElementId) match
          case (e: EdgeElement, n: NodeId) =>
            Arrow.fromArrowId(e.elementId).exists(a => if action.endpoint.isSource then a.source == n else a.target == n)
          case _ => false
        if !ignore then
          selection.set1(Set(start.elementId, endElementId))
        else
          selection.set2(start.elementId)

      case None =>
        selection.set2(start.elementId)

  // 4. Mouse is up, find the new endpoint
  def handleMoveArrowStartMouseUp(ev: dom.MouseEvent, action: MouseAction.MoveArrowEndpointAction): Unit =
    val selectionNow = selection.now()
    val originator   = action.originator
    selection.clear()
    // Check if the mouse release point (not the selection rectangle) is inside the source node's bounding box
    val isMouseInsideSourceNode = pointInsideBox((ev.clientX, ev.clientY), originator.ref.getBoundingClientRect())

    if selectionNow.size == 2 && !isMouseInsideSourceNode then
      // move the arrow endpoint to the new position
      (selectionNow - originator.elementId).head.asNodeId.foreach: endpointId =>
        moveArrowEndpoint(
          originator.arrowId.get,
          action.endpoint match
            case ArrowEndpoint.source => ArrowEndpointId.SourceId(endpointId)
            case ArrowEndpoint.target => ArrowEndpointId.TargetId(endpointId)
        )
