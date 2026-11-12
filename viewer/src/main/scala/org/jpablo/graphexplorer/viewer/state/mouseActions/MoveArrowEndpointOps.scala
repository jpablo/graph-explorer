package org.jpablo.graphexplorer.viewer.state.mouseActions

import org.jpablo.graphexplorer.viewer.components.selection.{EdgeElement, SelectableElement}
import org.jpablo.graphexplorer.viewer.models.ElementIds
import org.jpablo.graphexplorer.viewer.components.svgCanvas.{ArrowBetweenPointerAndEndpoint, ArrowEndpointControl, clientCoords}
import org.jpablo.graphexplorer.viewer.domUtils.elementsFromPoint
import org.jpablo.graphexplorer.viewer.models.{Arrow, ArrowEndpointId, NodeId}
import org.jpablo.graphexplorer.viewer.state.DiagramSelectionOps.findClosestElementId
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.state.mouseActions.MouseAction.{Inactive, MoveArrowEndpointAction}
import org.jpablo.graphexplorer.viewer.domUtils.DomEvent
import org.jpablo.graphexplorer.viewer.utils.{ClientPoint, MouseActionRect}
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.ArrowPosition

/*
 * This trait contains the logic for handling mouse actions related to moving the start of an arrow in the graph.
 * It includes methods for handling mouse events, updating the arrow's position, and rendering the arrow.
 */
trait MoveArrowEndpointOps:
  this: ViewerState =>

  // 1. Create the UI control
  def handleArrowEndpointControl(parent: dom.svg.G, elem: Option[SelectableElement], action: MouseAction, edgePositions: Map[String, ArrowPosition]): Unit =
    val showControl =
      action match
        case Inactive                   => true
        case a: MoveArrowEndpointAction => a.rect.isEmpty
        case _                          => false
    val controls =
      elem.toArray.flatMap:
        case edge: EdgeElement if showControl =>
          visibleArrowOf(edge) match {
            case Some(arrow) =>
              // Convert NodeIds to SelectableElements
              val nodeIds = Set(arrow.source, arrow.target)
              val elementIds = ElementIds(nodeIds)
              val selectableElements = SelectableElement.query(parent, elementIds, selectionStrategyNow())
              val nodeElementsMap = selectableElements.collect { 
                case ne if ne.nodeId.isDefined => ne.nodeId.get -> ne 
              }.toMap
              
              for
                endpoint <- ArrowEndpoint.values
                endpointNodeId = if (endpoint == ArrowEndpoint.source) arrow.source else arrow.target
                endpointElement = nodeElementsMap.get(endpointNodeId)
                elem = ArrowEndpointControl(edge, endpoint, clientSize, endpointElement, edgePositions).ref
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
            case None => 
              Array.empty[dom.svg.G]
          }
        case _ =>
          Array.empty[dom.svg.G]

    // Always clear previous controls first (like handleNewArrowControls): appending
    // without clearing stacked duplicate disks (with live listeners) on every
    // click-without-drag cycle.
    parent.querySelectorAll("g.edge-endpoint-disk").foreach(_.remove())
    controls.foreach(parent.appendChild)

  /** The arrow of the edge element `edge`, from the visible graph. The canvas
    * shows the visible graph, and an arrow to a collapsed box is only there.
    *
    * The text of the arrow id does not give the two end nodes: it also holds
    * the ports (`a:f0->b/1`), and a node id can hold `->` or outer spaces.
    */
  private def visibleArrowOf(edge: SelectableElement): Option[Arrow] =
    edge.arrowId.flatMap(visibleGraphNow().arrows.get)

  /** True when the pointer at `point`, over the node `n`, is on the end
    * `endpoint` of `arrow` now. A drop there does not change the arrow.
    *
    * The node must be the end node. When the end has a port and the pointer
    * is over a cell, the cell must have that port too. Thus a drop on a
    * different cell of the same record moves the port. An end with no port,
    * or a port on a node with no cells (a compass point), compares only the
    * node. The port is the port that the drawing uses (see
    * `Arrow.drawnSourcePort`).
    *
    * This runs on each mouse move, thus it does one cell hit test, and it
    * reads the port from the cell box that the hit test gives.
    */
  private def isCurrentEnd(arrow: Arrow, endpoint: ArrowEndpoint, n: NodeId, point: ClientPoint): Boolean =
    val (node, port) =
      if endpoint.isSource then (arrow.source, arrow.drawnSourcePort)
      else (arrow.target, arrow.drawnTargetPort)
    node == n && port.forall: p =>
      recordCells.cellBoxAtClientPoint(n, point.x, point.y).forall(_.port.contains(p.takeWhile(_ != ':')))

  // 2. Draw a dynamic arrow that follows the pointer
  // see: [[org.jpablo.graphexplorer.viewer.components.svgCanvas.ArrowBetweenPointerAndEndpoint]]

  // 3. Update the selection as the pointer is moving
  def onMoveArrowSourceAction(action: MouseAction.MoveArrowEndpointAction) =
    val start     = action.originator
    val neighbors = dom.document.elementsFromPoint(action.rect.end.x, action.rect.end.y)

    val strategy = selectionStrategyNow()
    findClosestElementId(neighbors, strategy = strategy, selector = Some(strategy.nodeSelector)) match
      case Some(targetElementId) =>
        val ignore = (start, targetElementId) match
          case (e: EdgeElement, n: NodeId) =>
            visibleArrowOf(e).exists(isCurrentEnd(_, action.endpoint, n, action.rect.end))
          case _ => false
        if !ignore then
          selection.set1(Set(start.elementId, targetElementId))
        else
          selection.set2(start.elementId)

      case None =>
        selection.set2(start.elementId)

  // 4. Mouse is up, find the new endpoint
  def handleMoveArrowStartMouseUp(ev: dom.MouseEvent, action: MouseAction.MoveArrowEndpointAction): Unit =
    val selectionNow = selection.now()
    val originator   = action.originator
    // Check if the mouse release point (not the selection rectangle) is inside the source node's bounding box
    val isMouseInsideSourceNode = pointInsideBox((ev.clientX, ev.clientY), originator.ref.getBoundingClientRect())

    if selectionNow.size == 2 && !isMouseInsideSourceNode then
      // move the arrow endpoint to the new position
      (selectionNow - originator.elementId).head.asNodeId.foreach: endpointId =>
        // Dropping on a record re-anchors the endpoint at the CELL under the
        // pointer (its port, minted on commit when the cell has none).
        val dropCell = recordCells.cellPathAtClientPoint(endpointId, ev.clientX, ev.clientY)
        moveArrowEndpoint(
          originator.arrowId.get,
          action.endpoint match
            case ArrowEndpoint.source => ArrowEndpointId.SourceId(endpointId)
            case ArrowEndpoint.target => ArrowEndpointId.TargetId(endpointId)
          ,
          dropCell
        )

  def addArrowBetweenPointerAndEndpoint(rootGroup: dom.svg.G, action: MoveArrowEndpointAction): Unit =
    rootGroup.appendChild(ArrowBetweenPointerAndEndpoint(action, rootGroup).ref)
