package org.jpablo.graphexplorer.viewer.state.mouseActions

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.components.selection.EdgeElement
import org.jpablo.graphexplorer.viewer.components.svgCanvas.{ArrowEndpointControl, clientCoords}
import org.jpablo.graphexplorer.viewer.components.toSvgPoint
import org.jpablo.graphexplorer.viewer.domUtils.elementsFromPoint
import org.jpablo.graphexplorer.viewer.formats.svg.PathCommand.*
import org.jpablo.graphexplorer.viewer.formats.svg.{PathCommand, SVGPathParser}
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
  def ArrowBetweenPointerAndEndpoint(
      action:    MouseAction.MoveArrowEndpointAction,
      rootGroup: dom.svg.G
  ): Option[ReactiveSvgElement[dom.svg.Path]] =
    if action.rect.isEmpty then
      None
    else
      val clonedPath = action.originator.ref.querySelector("path").cloneNode().asInstanceOf[dom.svg.Path]
      val pathData   = clonedPath.getAttribute("d")
      val point      = action.rect.end.toSvgPoint(rootGroup.getScreenCTM())

      def updateOrigin(commands: List[PathCommand]) =
        commands match
          case MoveTo(a, _ :: pt) :: ct => MoveTo(a, point.toTuple :: pt) :: ct
          case other                    => other

      def updateTarget(commands: List[PathCommand]) =
        commands match
          case commands =>
            // Find the last command to update the target point
            val lastIndex = commands.size - 1
            commands.zipWithIndex.map:
              case (LineTo(a, pts), i) if i == lastIndex     => LineTo(a, pts.init :+ point.toTuple)
              case (CurveTo(a, points), i) if i == lastIndex =>
                // For CurveTo, we need to update the last point in the last triplet
                val updatedPoints = points.init :+ (points.last._1, points.last._2, point.toTuple)
                CurveTo(a, updatedPoints)
              case (cmd, _) => cmd

      val updatedPathData = SVGPathParser.parse(pathData)
        .map(if action.endpoint.isSource then updateOrigin else updateTarget)
        .map(PathCommand.toData)
        .getOrElse(pathData)

      clonedPath.setAttribute("d", updatedPathData)
      clonedPath.setAttribute("stroke", "#2c70ff")
      clonedPath.setAttribute("stroke-dasharray", "2 2")
      Some(foreignSvgElement(svg.path, clonedPath))

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
          selection.set3(Set(start.elementId, endElementId))

      case None =>
        selection.set(start.elementId)

  // 4. Mouse is up, find the new endpoint
  def handleMoveArrowStartMouseUp(ev: dom.MouseEvent, action: MouseAction.MoveArrowEndpointAction): Unit =
    val selectionNow = selection.now()
    val originator   = action.originator
    selection.clear()
    // Check if the mouse release point (not the selection rectangle) is inside the source node's bounding box
    val startBbox         = originator.ref.getBoundingClientRect()
    val mouseReleasePoint = (ev.clientX, ev.clientY)
    val isMouseInsideSourceNode =
      mouseReleasePoint._1 >= startBbox.left &&
        mouseReleasePoint._1 <= startBbox.right &&
        mouseReleasePoint._2 >= startBbox.top &&
        mouseReleasePoint._2 <= startBbox.bottom

    if selectionNow.size == 2 && !isMouseInsideSourceNode then
      // move the arrow endpoint to the new position
      (selectionNow - originator.elementId).head.asNodeId.foreach: endpointId =>
        moveArrowEndpoint(
          originator.arrowId.get,
          action.endpoint match
            case ArrowEndpoint.source => ArrowEndpointId.SourceId(endpointId)
            case ArrowEndpoint.target => ArrowEndpointId.TargetId(endpointId)
        )
