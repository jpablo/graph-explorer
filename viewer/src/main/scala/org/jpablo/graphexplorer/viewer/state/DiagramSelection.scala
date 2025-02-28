package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.components.{Action, Point2d, SelectionRect}
import org.jpablo.graphexplorer.viewer.extensions.*
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.{Arrow, NodeId}
import com.softwaremill.quicklens.*

import scala.scalajs.js

type SelectedNodes = Set[NodeId]

class DiagramSelectionOps:
  private val selectedNodes: Var[SelectedNodes] = Var(Set.empty)

  val signal = selectedNodes.signal
    .distinct
//    .tapEach(s => if s.nonEmpty then dom.console.log(s"Selection: $s"))

  def now(): SelectedNodes = selectedNodes.now()

  def toggle(ss: NodeId*): Unit = selectedNodes.update(ss.foldLeft(_)(_.toggle(_)))

  def set(ss: SelectedNodes): Unit =
    selectedNodes.set(ss)

  def add(ss: SelectedNodes): Unit =
    val current = now()
    val newNodes = ss -- current
    if newNodes.nonEmpty then set(current ++ newNodes)

  def remove(ss: SelectedNodes): Unit =
    val current = now()
    val nodesToRemove = ss intersect current
    if nodesToRemove.nonEmpty then set(current -- nodesToRemove)

  // def contains(s: NodeId): Boolean = selectedNodes.now().contains(s)

  def clear(): Unit = set(Set.empty)

  val selectSuccessors = selectRelated(_.allSuccessorsGraph(_))
  val selectPredecessors = selectRelated(_.allPredecessorsGraph(_))
  val selectDirectSuccessors = selectRelated(_.directSuccessorsGraph(_))
  val selectDirectPredecessors = selectRelated(_.directPredecessorsGraph(_))

  private def selectRelated(
      selector: (ViewerGraph, SelectedNodes) => ViewerGraph
  )(fullGraph: ViewerGraph, hiddenNodes: HiddenNodes): Unit =
    val visibleSubGraph: ViewerGraph = fullGraph.removeNodes(hiddenNodes)
    val relatedSubGraph: ViewerGraph = selector(visibleSubGraph, selectedNodes.now())
    // Incorrect: relatedSubGraph.allArrowIds selects the wrong arrowIds
    val relatedIds = relatedSubGraph.allNodeIds ++ relatedSubGraph.allArrowIds
    add(relatedIds)

  def handleClickOnNode(nodeId: NodeId)(shiftKey: Boolean) =
    if shiftKey then
      toggle(nodeId)
    else
      set(Set(nodeId))

  def handleClickOnArrow(arrow: Arrow)(metaKey: Boolean) =
    val nodeId = arrow.id
    if metaKey then
      toggle(nodeId)
    else
      set(Set(nodeId))

  // -----------

  val selectionRectArea: Var[Option[Action.Area]] = Var(None)
  val selectionRectLine: Var[Option[Action.Line]] = Var(None)

  def startSelectionArea(pos: Point2d[Double], shift: Boolean): Unit =
    selectionRectArea.set(Some(Action.Area(SelectionRect(pos.x, pos.y, pos.x, pos.y, shift))))

  def startSelectionLine(pos: Point2d[Double], shift: Boolean, start: SelectableElement): Unit =
    selectionRectLine.set(Some(Action.Line(SelectionRect(pos.x, pos.y, pos.x, pos.y, shift), start)))

  def updateSelection(pos: Point2d[Double], shift: Boolean): Unit =
    selectionRectArea.update(_.map(_.modify(_.rect).using(_.copy(endX = pos.x, endY = pos.y, shift = shift))))
    selectionRectLine.update(_.map(_.modify(_.rect).using(_.copy(endX = pos.x, endY = pos.y, shift = shift))))

  def endSelectionArea(): Unit =
    selectionRectArea.set(None)

  def endSelectionLine(): Unit =
    selectionRectLine.set(None)

  def handleSelectionLineUpdate(
      rect:                SelectionRect,
      start:               SelectableElement,
      elementsFromRectEnd: js.Array[dom.Element]
  ) =
    // Make sure only start or (start,end) nodes are selected when creating a new edge
    // For now only allow a line selection into nodes
    findNode(rect, elementsFromRectEnd, "g.node") match
      case Some(end) => set(Set(start.nodeId, end))
      case None      => set(Set(start.nodeId))

  def handleSelectionAreaUpdate(
      rect:                SelectionRect,
      selectableElements:  Seq[SelectableElement],
      elementsFromRectEnd: js.Array[dom.Element]
  ) =
    // This is meant to capture a single click.
    if rect.isEmpty then
      findNode(rect, elementsFromRectEnd) match
        case Some(end) => handleClickOnNode(end)(rect.shift)
        case None      => clear()
    else
      val nodesInRect = selectableElements.filter(isNodeInRect(_, rect)).map(_.nodeId).toSet
      if nodesInRect.nonEmpty then
        if rect.shift then
          add(nodesInRect)
        else
          set(nodesInRect)
      else if !rect.shift then
        clear()

  /** Finds the node ID at the given selection rectangle's end point
    */
  private def findNode(
      rect:     SelectionRect,
      elements: js.Array[dom.Element],
      selector: String = "g.node, g.edge, g.cluster"
  ): Option[NodeId] =
    elements
      .filter(_.namespaceURI == "http://www.w3.org/2000/svg")
      .flatMap(element => Option(element.closest(selector)))
      .distinct
      .map(SelectableElement.fromDomElement)
      .collectFirst:
        case Some(elem) => elem.nodeId

  /** Checks if a selectable element intersects with a selection rectangle
    *
    * @param elem
    *   The selectable element to check
    * @param rect
    *   The selection rectangle in client coordinates
    * @return
    *   true if the element's bounding box intersects with the selection rectangle
    *
    * The method:
    *   1. Gets the element's bounding box in client coordinates 2. Normalizes the selection rect coordinates to handle
    *      any direction of dragging 3. Uses a standard rectangle intersection test
    */
  def isNodeInRect(elem: SelectableElement, rect: SelectionRect): Boolean =
    val bbox = elem.get.getBoundingClientRect()
    val normalizedRect = (
      x      = rect.startX.min(rect.endX),
      y      = rect.startY.min(rect.endY),
      width  = math.abs(rect.endX - rect.startX),
      height = math.abs(rect.endY - rect.startY)
    )
    !(bbox.right < normalizedRect.x ||
      bbox.left > normalizedRect.x + normalizedRect.width ||
      bbox.bottom < normalizedRect.y ||
      bbox.top > normalizedRect.y + normalizedRect.height)

end DiagramSelectionOps
