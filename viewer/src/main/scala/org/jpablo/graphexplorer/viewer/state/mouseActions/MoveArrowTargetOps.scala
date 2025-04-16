package org.jpablo.graphexplorer.viewer.state.mouseActions

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.components.selection.EdgeElement
import org.jpablo.graphexplorer.viewer.components.svgCanvas.{ArrowEndpointButton, clientCoords}
import org.jpablo.graphexplorer.viewer.components.toSvgPoint
import org.jpablo.graphexplorer.viewer.domUtils.elementsFromPoint
import org.jpablo.graphexplorer.viewer.formats.svg.PathCommand.{CurveTo, LineTo}
import org.jpablo.graphexplorer.viewer.formats.svg.{PathCommand, SVGPathParser}
import org.jpablo.graphexplorer.viewer.models.{Arrow, NodeId}
import org.jpablo.graphexplorer.viewer.state.DiagramSelectionOps.findClosestElementId
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.state.mouseActions.MouseAction.MoveArrowTargetAction
import org.jpablo.graphexplorer.viewer.utils.UserActionRect

/*
 * This trait contains the logic for handling mouse actions related to moving the target of an arrow in the graph.
 * It includes methods for handling mouse events, updating the arrow's position, and rendering the arrow.
 */
trait MoveArrowTargetOps:
  this: ViewerState =>

  def handleMoveArrowTargetMouseUp(ev: dom.MouseEvent, lastActionValue: MouseAction.MoveArrowTargetAction): Unit =
    val current = selection.now()
    val start   = lastActionValue.start
    selection.clear()
    // Check if the mouse release point (not the selection rectangle) is inside the source node's bounding box
    val startBbox         = start.get.getBoundingClientRect()
    val mouseReleasePoint = (ev.clientX, ev.clientY)
    val isMouseInsideSourceNode =
      mouseReleasePoint._1 >= startBbox.left &&
        mouseReleasePoint._1 <= startBbox.right &&
        mouseReleasePoint._2 >= startBbox.top &&
        mouseReleasePoint._2 <= startBbox.bottom

    if current.size == 2 && !isMouseInsideSourceNode then
      pprint.log((current, start.elementId))
      // move the arrow target point to the new position
      (current - start.elementId).head.asNodeId.foreach(end => moveArrowTarget(start.arrowId.get, end))

  def onMoveArrowTargetAction(action: MouseAction.MoveArrowTargetAction) =
    val start     = action.start
    val neighbors = dom.document.elementsFromPoint(action.rect.end.x, action.rect.end.y)

    findClosestElementId(neighbors, "g.node") match
      case Some(endElementId) =>
        val ignore = (start, endElementId) match
          case (e: EdgeElement, n: NodeId) => Arrow.fromArrowId(e.elementId).exists(_.target == n)
          case _                           => false
        if !ignore then
          selection.set3(Set(start.elementId, endElementId))

      case None =>
        selection.set(start.elementId)

  def arrowFromSourceToPointer(
      action:    MouseAction.MoveArrowTargetAction,
      rootGroup: dom.svg.G
  ): Option[ReactiveSvgElement[dom.svg.Path]] =
    if action.rect.isEmpty then
      None
    else
      val clonedPath = action.start.get.querySelector("path").cloneNode().asInstanceOf[dom.svg.Path]
      val pathData   = clonedPath.getAttribute("d")
      val point      = action.rect.end.toSvgPoint(rootGroup.getScreenCTM())

      def updateTarget(commands: List[PathCommand]) =
        commands match
          case commands =>
            // Find the last command to update the target point
            val lastIndex = commands.size - 1
            commands.zipWithIndex.map {
              case (LineTo(a, pts), i) if i == lastIndex     => LineTo(a, pts.init :+ point.toTuple)
              case (CurveTo(a, points), i) if i == lastIndex =>
                // For CurveTo, we need to update the last point in the last triplet
                val updatedPoints = points.init :+ (points.last._1, points.last._2, point.toTuple)
                CurveTo(a, updatedPoints)
              case (cmd, _) => cmd
            }

      val updatedPathData = SVGPathParser.parse(pathData).map(updateTarget).map(PathCommand.toData).getOrElse(pathData)
      clonedPath.setAttribute("d", updatedPathData)
      clonedPath.setAttribute("stroke", "#2c70ff")
      clonedPath.setAttribute("stroke-dasharray", "2 2")
      Some(foreignSvgElement(svg.path, clonedPath))

  def buildArrowTargetEndpointButton(edge: EdgeElement) =
    ArrowEndpointButton(
      edge,
      start = false,
      onMouseDown.stopPropagation.map(clientCoords) --> { (pos, _) =>
        mouseAction.start(MoveArrowTargetAction(UserActionRect(start = pos, end = pos, shift = false), start = edge))
      },
      onMouseUp.stopPropagation --> mouseAction.inactive()
    )
