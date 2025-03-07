package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.state.Var
import com.softwaremill.quicklens.*
import org.jpablo.graphexplorer.viewer.components.Action
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.{Arrow, ElementId, ElementIds}
import org.jpablo.graphexplorer.viewer.utils.{ClientPoint, UserActionRect}

import scala.annotation.targetName
import scala.scalajs.js

type SelectedNodes = ElementIds

class DiagramSelectionOps:
  private val selectedNodes: Var[SelectedNodes] = Var(ElementIds())

  val signal = selectedNodes.signal
    .distinct
//    .tapEach(s => if s.nonEmpty then dom.console.log(s"Selection: $s"))

  def now(): SelectedNodes = selectedNodes.now()

  def toggle(ss: ElementId*): Unit = selectedNodes.update(ss.foldLeft(_)(_.toggle(_)))

  def set(ss: ElementId*): Unit =
    set(ss.toSet)

  def set(ss: SelectedNodes): Unit =
    selectedNodes.set(ss)

  @targetName("setElementIds")
  def set(ss: Set[? <: ElementId]): Unit =
    set(ElementIds(ss))

  @targetName("addElementIds")
  def add(ss: Set[? <: ElementId]): Unit =
    add(ElementIds(ss))

  def add(ss: SelectedNodes): Unit =
    val current = now()
    val newNodes = ss -- current
    if newNodes.nonEmpty then set(current ++ newNodes)

  def remove(ss: SelectedNodes): Unit =
    val current = now()
    val nodesToRemove = ss intersect current
    if nodesToRemove.nonEmpty then set(current -- nodesToRemove)

  // def contains(s: NodeId): Boolean = selectedNodes.now().contains(s)

  def clear(): Unit = set(ElementIds())

  val selectSuccessors = selectRelated((graph, nodes) => graph.allSuccessorsGraph(nodes.nodeIds))
  val selectPredecessors = selectRelated((graph, nodes) => graph.allPredecessorsGraph(nodes.nodeIds))
  val selectDirectSuccessors = selectRelated((graph, nodes) => graph.directSuccessorsGraph(nodes.nodeIds))
  val selectDirectPredecessors = selectRelated((graph, nodes) => graph.directPredecessorsGraph(nodes.nodeIds))

  private def selectRelated(
      selector: (ViewerGraph, SelectedNodes) => ViewerGraph
  )(fullGraph: ViewerGraph, hiddenNodes: HiddenElements): Unit =
    val visibleSubGraph: ViewerGraph = fullGraph.removeElements(hiddenNodes)
    val relatedSubGraph: ViewerGraph = selector(visibleSubGraph, selectedNodes.now())
    // Incorrect: relatedSubGraph.allArrowIds selects the wrong arrowIds
    add(relatedSubGraph.nodeIds ++ relatedSubGraph.arrowIds)

  def handleClickOnNode(elementId: ElementId)(shiftKey: Boolean) =
    if shiftKey then
      toggle(elementId)
    else
      set(ElementIds.from(elementId))

  def handleClickOnArrow(arrow: Arrow)(shiftKey: Boolean) =
    val nodeId = arrow.id
    if shiftKey then
      toggle(nodeId)
    else
      set(ElementIds.from(nodeId))

  // -----------

  val selectionRectArea: Var[Option[Action.Area]] = Var(None)
  val selectionRectLine: Var[Option[Action.Line]] = Var(None)

  def startSelectionArea(pos: ClientPoint, shift: Boolean): Unit =
    selectionRectArea.set(Some(Action.Area(UserActionRect(pos, pos, shift))))

  def startSelectionLine(pos: ClientPoint, shift: Boolean, start: SelectableElement): Unit =
    selectionRectLine.set(Some(Action.Line(UserActionRect(pos, pos, shift), start)))

  def updateSelection(pos: ClientPoint, shift: Boolean): Unit =
    selectionRectArea.update(_.map(_.modify(_.rect).using(_.copy(end = pos, shift = shift))))
    selectionRectLine.update(_.map(_.modify(_.rect).using(_.copy(end = pos, shift = shift))))

  def endSelectionArea(): Unit =
    selectionRectArea.set(None)

  def endSelectionLine(): Unit =
    selectionRectLine.set(None)

  def handleSelectionLineUpdate(
      start:               SelectableElement,
      elementsFromRectEnd: js.Array[dom.Element]
  ) =
    // Make sure only start or (start,end) nodes are selected when creating a new edge
    // For now only allow a line selection into nodes
    findNode(elementsFromRectEnd, "g.node") match
      case Some(end) => set(Set(start.elementId, end))
      case None      => set(start.elementId)

  def handleSelectionAreaUpdate(
      rect:                UserActionRect,
      selectableElements:  Seq[SelectableElement],
      elementsFromRectEnd: js.Array[dom.Element]
  ) =
    // This is meant to capture a single click.
    if rect.isEmpty then
      findNode(elementsFromRectEnd) match
        case Some(end) => handleClickOnNode(end)(rect.shift)
        case None      => clear()
    else
      val nodesInRect = selectableElements.filter(isNodeInRect(_, rect)).map(_.elementId).toSet
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
      elements: js.Array[dom.Element],
      selector: String = "g.node, g.edge, g.cluster"
  ): Option[ElementId] =
    elements
      .filter(_.namespaceURI == "http://www.w3.org/2000/svg")
      .flatMap(element => Option(element.closest(selector)))
      .distinct
      .map(SelectableElement.fromDomElement)
      .collectFirst:
        case Some(elem) => elem.elementId

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
  def isNodeInRect(elem: SelectableElement, rect: UserActionRect): Boolean =
    val bbox = elem.get.getBoundingClientRect()
    val x      = rect.start.x min rect.end.x
    val y      = rect.start.y min rect.end.y
    val width  = math.abs(rect.end.x - rect.start.x)
    val height = math.abs(rect.end.y - rect.start.y)
    !(bbox.right < x ||
      bbox.left > x + width ||
      bbox.bottom < y ||
      bbox.top > y + height)

end DiagramSelectionOps
