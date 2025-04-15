package org.jpablo.graphexplorer.viewer.state.mouseActions

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.components.svgCanvas.NewArrowButton
import org.jpablo.graphexplorer.viewer.domUtils.elementsFromPoint
import org.jpablo.graphexplorer.viewer.state.DiagramSelectionOps.findFirstElementId
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.state.mouseActions.MouseAction.AddNewArrowAction
import org.jpablo.graphexplorer.viewer.utils.{ClientPoint, UserActionRect}

import scala.scalajs.js

trait AddNewArrowOps:
  this: ViewerState =>

  def handleAddNewArrowMouseUp(ev: dom.MouseEvent, lastActionValue: AddNewArrowAction): Unit =
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

    // Single selection and mouse released on the source node: add a self loop
    if current.size == 1 && isMouseInsideSourceNode then
      start.nodeId.foreach(nodeId => addArrow(nodeId, nodeId))
    else if current.size == 2 then
      (current - start.elementId).head.asNodeId.foreach(end => addArrow(start.nodeId.get, end))

  def onAddNewArrowAction(action: AddNewArrowAction) =
    selectAddNewArrowEndpoints(
      start = action.start,
      elementsFromRectEnd = dom.document.elementsFromPoint(action.rect.end.x, action.rect.end.y)
    )

  def selectAddNewArrowEndpoints(
      start:               SelectableElement,
      elementsFromRectEnd: js.Array[dom.Element]
  ) =
    // Make sure only start or (start,end) nodes are selected when creating a new arrow
    // For now only allow a line selection into nodes
    findFirstElementId(elementsFromRectEnd, "g.node") match
      case Some(endElementId) => selection.set(Set(start.elementId, endElementId))
      case None               => selection.set(start.elementId)

  def buildNewArrowButton(selectedElem: SelectableElement): Option[ReactiveSvgElement[dom.svg.G]] =
    NewArrowButton(
      selectedElem,
      graphRankDir.observe().now,
      onMouseDown.stopPropagation --> { ev =>
        val pos = ClientPoint(ev.clientX, ev.clientY)
        mouseAction.start(AddNewArrowAction(UserActionRect(pos, pos, shift = false), selectedElem))

      },
      onMouseUp.stopPropagation --> { _ =>
        mouseAction.inactive()
        addNodeWithSmartConnection()
      }
    )

