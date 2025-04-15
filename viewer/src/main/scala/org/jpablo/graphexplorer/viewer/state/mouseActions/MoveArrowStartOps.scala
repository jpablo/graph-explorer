package org.jpablo.graphexplorer.viewer.state.mouseActions

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.components.svgCanvas.{ArrowWithEndpoint, NewArrowButton}
import org.jpablo.graphexplorer.viewer.domUtils.elementsFromPoint
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Rankdir
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.utils.ClientPoint

trait MoveArrowStartOps:
  this: ViewerState =>

  def handleMoveArrowStartMouseUp(ev: dom.MouseEvent, lastActionValue: MouseAction.MoveArrowStartAction): Unit =
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

  def onMoveArrowStart(action: MouseAction.MoveArrowStartAction) =
    selectAddNewArrowEndpoints(
      start = action.start,
      elementsFromRectEnd = dom.document.elementsFromPoint(action.rect.end.x, action.rect.end.y)
    )

  def buildArrowWithEndpoint(groupRef: dom.svg.G) =
    ArrowWithEndpoint(mouseAction.moveArrowStartAction, groupRef)

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
