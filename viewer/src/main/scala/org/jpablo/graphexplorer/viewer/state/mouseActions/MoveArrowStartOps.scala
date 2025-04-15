package org.jpablo.graphexplorer.viewer.state.mouseActions

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.components.svgCanvas.NewArrowButton
import org.jpablo.graphexplorer.viewer.components.toSvgPoint
import org.jpablo.graphexplorer.viewer.domUtils.elementsFromPoint
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Rankdir
import org.jpablo.graphexplorer.viewer.formats.svg.Command.MoveTo
import org.jpablo.graphexplorer.viewer.formats.svg.{Command, SVGPathParser}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.utils.ClientPoint

trait MoveArrowStartOps:
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

  def onMoveArrowStart(action: MouseAction.MoveArrowSourceAction) =
    selectAddNewArrowEndpoints(
      start = action.start,
      elementsFromRectEnd = dom.document.elementsFromPoint(action.rect.end.x, action.rect.end.y)
    )

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

      def updateOrigin(commands: List[Command]) =
        commands match
          case MoveTo(a, _ :: pt) :: ct => MoveTo(a, point.toTuple :: pt) :: ct
          case other                    => other

      val updatedPathData = SVGPathParser.parse(pathData).map(updateOrigin).map(Command.toData).getOrElse(pathData)
      clonedPath.setAttribute("d", updatedPathData)
      Some(foreignSvgElement(svg.path, clonedPath))

  def buildNewArrowButton(selectedElem: SelectableElement) =
    NewArrowButton(
      selectedElem,
      graphRankDir.observe().now,
      onMouseDown.stopPropagation --> { ev =>
        mouseAction.startAddNewArrow(ClientPoint(ev.clientX, ev.clientY), shift = false, selectedElem)
      },
      onMouseUp.stopPropagation --> { _ =>
        mouseAction.inactive()
        addNodeWithSmartConnection()
      }
    )
