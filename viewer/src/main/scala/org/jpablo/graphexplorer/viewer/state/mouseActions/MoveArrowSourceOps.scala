package org.jpablo.graphexplorer.viewer.state.mouseActions

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.components.selection.{EdgeElement, SelectableElement}
import org.jpablo.graphexplorer.viewer.components.svgCanvas.{ArrowEndpointButton, clientCoords}
import org.jpablo.graphexplorer.viewer.components.toSvgPoint
import org.jpablo.graphexplorer.viewer.domUtils.elementsFromPoint
import org.jpablo.graphexplorer.viewer.formats.svg.PathCommand.MoveTo
import org.jpablo.graphexplorer.viewer.formats.svg.{PathCommand, SVGPathParser}
import org.jpablo.graphexplorer.viewer.models.{Arrow, NodeId}
import org.jpablo.graphexplorer.viewer.state.DiagramSelectionOps.findClosestElementId
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.state.mouseActions.MouseAction.MoveArrowSourceAction
import org.jpablo.graphexplorer.viewer.utils.UserActionRect

/*
 * This trait contains the logic for handling mouse actions related to moving the start of an arrow in the graph.
 * It includes methods for handling mouse events, updating the arrow's position, and rendering the arrow.
 */
trait MoveArrowSourceOps:
  this: ViewerState =>

  def handleMoveArrowStartMouseUp(ev: dom.MouseEvent, lastActionValue: MouseAction.MoveArrowSourceAction): Unit =
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
      // move the arrow start point to the new position
      (current - start.elementId).head.asNodeId.foreach(end => moveArrowSource(start.arrowId.get, end))

  def onMoveArrowSourceAction(action: MouseAction.MoveArrowSourceAction) =
    val start     = action.start
    val neighbors = dom.document.elementsFromPoint(action.rect.end.x, action.rect.end.y)

    findClosestElementId(neighbors, "g.node") match
      case Some(endElementId) =>
        val ignore = (start, endElementId) match
          case (e: EdgeElement, n: NodeId) => Arrow.fromArrowId(e.elementId).exists(_.source == n)
          case _                           => false
        if !ignore then
          selection.set3(Set(start.elementId, endElementId))

      case None =>
        selection.set(start.elementId)

  def ArrowFromPointerToTarget(
      action:    MouseAction.MoveArrowSourceAction,
      rootGroup: dom.svg.G
  ): Option[ReactiveSvgElement[dom.svg.Path]] =
    if action.rect.isEmpty then
      None
    else
      val clonedPath = action.start.get.querySelector("path").cloneNode().asInstanceOf[dom.svg.Path]
      val pathData   = clonedPath.getAttribute("d")
      val point      = action.rect.end.toSvgPoint(rootGroup.getScreenCTM())

      def updateOrigin(commands: List[PathCommand]) =
        commands match
          case MoveTo(a, _ :: pt) :: ct => MoveTo(a, point.toTuple :: pt) :: ct
          case other                    => other

      val updatedPathData = SVGPathParser.parse(pathData).map(updateOrigin).map(PathCommand.toData).getOrElse(pathData)
      clonedPath.setAttribute("d", updatedPathData)
      clonedPath.setAttribute("stroke", "#2c70ff")
      clonedPath.setAttribute("stroke-dasharray", "2 2")
      Some(foreignSvgElement(svg.path, clonedPath))

  def buildArrowEndpointButton(selectedElem: SelectableElement) =
    selectedElem match
      case edge: EdgeElement => Seq(
          ArrowEndpointButton(
            edge,
            true,
            onMouseDown.stopPropagation.map(clientCoords) --> { (pos, _) =>
              mouseAction.start(MoveArrowSourceAction(UserActionRect(start = pos, end = pos, shift = false), start = selectedElem))
            },
            onMouseUp.stopPropagation --> mouseAction.inactive()
          ),
          ArrowEndpointButton(
            edge,
            false
          )
        )
      case _ => Seq.empty
