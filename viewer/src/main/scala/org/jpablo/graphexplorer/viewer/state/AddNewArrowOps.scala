package org.jpablo.graphexplorer.viewer.state

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.components.selection.{EdgeElement, SelectableElement}
import org.jpablo.graphexplorer.viewer.components.svgCanvas.{ArrowEndpointButton, DraggingArrow}
import org.jpablo.graphexplorer.viewer.domUtils.elementsFromPoint
import org.jpablo.graphexplorer.viewer.state.DiagramSelectionOps.findFirstElementId
import org.jpablo.graphexplorer.viewer.utils.ClientPoint

import scala.scalajs.js

trait AddNewArrowOps:
  this: ViewerState =>

  def handleAddNewArrowMouseUp(ev: dom.MouseEvent, lastActionValue: MouseAction.AddNewArrowAction): Unit =
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

  def onAddNewArrowAction(actionO: Option[MouseAction.AddNewArrowAction]) =
    for action <- actionO do
      selectAddNewArrowEndpoints(
        action.start,
        dom.document.elementsFromPoint(action.rect.end.x, action.rect.end.y)
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

  def buildArrowEndpointButton(selectedElem: SelectableElement): Seq[ReactiveSvgElement[dom.svg.G]] =
    selectedElem match
      case edge: EdgeElement => Seq(
          ArrowEndpointButton(
            edge,
            true,
            onMouseDown.stopPropagation --> { ev =>
              mouseAction.startMoveArrowStart(ClientPoint(ev.clientX, ev.clientY), shift = false, selectedElem)
            },
            onMouseUp.stopPropagation --> { _ =>
              mouseAction.inactive()
              addNodeWithSmartConnection()
            }
          )
        )
      case _ => Seq.empty

  def buildDraggingArrow(groupRef: dom.svg.G) =
    DraggingArrow(mouseAction.addNewArrowAction, groupRef)
