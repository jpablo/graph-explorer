package org.jpablo.graphexplorer.viewer.state.mouseActions

import org.jpablo.graphexplorer.viewer.components.selection.{EdgeElement, SelectableElement}
import org.jpablo.graphexplorer.viewer.components.svgCanvas.{ArrowBetweenPointerAndEndpoint, ArrowEndpointControl, clientCoords}
import org.jpablo.graphexplorer.viewer.domUtils.elementsFromPoint
import org.jpablo.graphexplorer.viewer.models.{Arrow, ArrowEndpointId, NodeId}
import org.jpablo.graphexplorer.viewer.state.DiagramSelectionOps.findClosestElementId
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.state.mouseActions.MouseAction.{Inactive, MoveArrowEndpointAction}
import org.jpablo.graphexplorer.viewer.utils.{DomEvent, MouseActionRect}

/*
 * This trait contains the logic for handling mouse actions related to moving the start of an arrow in the graph.
 * It includes methods for handling mouse events, updating the arrow's position, and rendering the arrow.
 */
trait MoveArrowEndpointOps:
  this: ViewerState =>

  // 1. Create the UI control
  def handleArrowEndpointControl(parent: dom.svg.G)(elem: Option[SelectableElement], action: MouseAction): Unit =
    val showControl =
      action match
        case Inactive                   => true
        case a: MoveArrowEndpointAction => a.rect.isEmpty
        case _                          => false
    val controls =
      elem.toArray.flatMap:
        case edge: EdgeElement if showControl =>
          for
            endpoint <- ArrowEndpoint.values
            elem = ArrowEndpointControl(edge, endpoint).ref
          yield
            elem.addEventListener(
              DomEvent.mousedown,
              (ev: dom.MouseEvent) => {
                ev.stopPropagation()
                val pos = clientCoords(ev)._1
                mouseAction.start(MoveArrowEndpointAction(MouseActionRect(start = pos, end = pos, shift = false), edge, endpoint))
              }
            )
            elem.addEventListener(DomEvent.mouseup, (ev: dom.MouseEvent) => { ev.stopPropagation(); mouseAction.inactive() })
            elem
        case _ =>
          Array.empty[dom.svg.G]

    if controls.nonEmpty then
      controls.foreach(parent.appendChild)
    else
      parent.querySelectorAll("g.edge-endpoint-disk").foreach(_.remove())

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

  def handleArrowBetweenPointerAndEndpoint(rootGroup: dom.svg.G, action: MoveArrowEndpointAction): Unit =
    rootGroup.appendChild(ArrowBetweenPointerAndEndpoint(action, rootGroup).ref)
