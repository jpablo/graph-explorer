package org.jpablo.graphexplorer.viewer.state.mouseActions

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.components.selection.{NodeElement, SelectableElement}
import org.jpablo.graphexplorer.viewer.components.svgCanvas.NewArrowControl
import org.jpablo.graphexplorer.viewer.domUtils.elementsFromPoint
import org.jpablo.graphexplorer.viewer.models.ArrowDirection
import org.jpablo.graphexplorer.viewer.state.DiagramSelectionOps.findClosestElementId
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.state.mouseActions.MouseAction.{AddNewArrowAction, Inactive}
import org.jpablo.graphexplorer.viewer.utils.{ClientPoint, MouseActionRect}
import org.scalajs.dom.DOMRect

import scala.scalajs.js

def pointInsideBox(
    pt:   (x: Double, y: Double),
    bbox: DOMRect
): Boolean =
  pt.x >= bbox.left &&
    pt.x <= bbox.right &&
    pt.y >= bbox.top &&
    pt.y <= bbox.bottom

trait AddNewArrowOps:
  this: ViewerState =>

  def handleAddNewArrowMouseUp(ev: dom.MouseEvent, action: AddNewArrowAction): Unit =
    val current = selection.now()
    val start   = action.originator

    // Check if the mouse release point (not the selection rectangle) is inside the source node's bounding box
    val isMouseInsideSourceNode = pointInsideBox(pt = (ev.clientX, ev.clientY), bbox = start.ref.getBoundingClientRect())
    // Single selection and mouse released on the source node: add a self-loop
    if current.size == 1 && isMouseInsideSourceNode then
      start.nodeId.foreach(nodeId => addArrow(nodeId, nodeId))
    else if current.size == 2 then
      (current - start.elementId).head.asNodeId.foreach: end =>
        action.direction match
          case ArrowDirection.forward  => addArrow(start.nodeId.get, end)
          case ArrowDirection.backward => addArrow(end, start.nodeId.get)

  def onAddNewArrowAction(action: AddNewArrowAction) =
    selectWithClosestNode(
      start = action.originator,
      elementsFromRectEnd = dom.document.elementsFromPoint(action.rect.end.x, action.rect.end.y)
    )

  def selectWithClosestNode(
      start:               SelectableElement,
      elementsFromRectEnd: js.Array[dom.Element]
  ) =
    // Make sure only start or (start,end) nodes are selected when creating a new arrow
    // For now only allow a line selection into nodes
    findClosestElementId(elementsFromRectEnd, "g.node") match
      case Some(endElementId) => selection.set1(Set(start.elementId, endElementId))
      case None               => selection.set2(start.elementId)

  def buildNewArrowControl(
      selectedElem:  SelectableElement,
      currentAction: MouseAction,
      direction:     ArrowDirection
  ): Option[ReactiveSvgElement[dom.svg.G]] =
    val showControl =
      currentAction match
        case Inactive             => true
        case a: AddNewArrowAction => a.rect.isEmpty
        case _                    => false

    selectedElem match
      case elem: NodeElement if showControl =>
        Some(
          NewArrowControl(
            elem,
            graphRankDir.observe().now,
            direction,
            onMouseDown.stopPropagation --> { ev =>
              val pos = ClientPoint(ev.clientX, ev.clientY)
              mouseAction.start(AddNewArrowAction(MouseActionRect(pos, pos, shift = false), selectedElem, direction))

            },
            onMouseUp.stopPropagation --> { _ =>
              mouseAction.inactive()
              addNodeWithSmartConnection(direction = direction)
            }
          )
        )
      case _ => None
