package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.EventStream
import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.components.selection.{SelectableElement, NodeElement, RecordCellElement}
import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.state.DiagramSelectionOps.findClosestElementId
import org.jpablo.graphexplorer.viewer.utils.MouseActionRect

import scala.annotation.targetName
import scala.scalajs.js

type Selection = ElementIds

trait DiagramSelectionOps:
  this: ViewerState =>

  private val selectionV: Var[Selection] = Var(ElementIds())

  val editingElementV = Var[Option[ElementId]](None)

  object selection:
    val signal = selectionV.signal
      .distinct
//     .tapEach(sel => println(s"[selection] $sel"))

    val selectionChanges: EventStream[(toUnselect: ElementIds, toSelect: ElementIds)] =
      selection.signal
        .scanLeft(curr => (ElementIds(), curr)):
          case ((_, curr), next) => (curr, next)
        .map: (curr, next) =>
          (
            toUnselect = curr.filter(id => !next.contains(id)),
            toSelect = next.filter(id => !curr.contains(id))
          )
        .distinct
        .changes

    val _selectSuccessors         = selectRelated((graph, nodes) => graph.allSuccessorsGraph(nodes.nodeIds))
    val _selectPredecessors       = selectRelated((graph, nodes) => graph.allPredecessorsGraph(nodes.nodeIds))
    val _selectDirectSuccessors   = selectRelated((graph, nodes) => graph.directSuccessorsGraph(nodes.nodeIds))
    val _selectDirectPredecessors = selectRelated((graph, nodes) => graph.directPredecessorsGraph(nodes.nodeIds))

    private def selectRelated(
        selector: (ViewerGraph, Selection) => ViewerGraph
    )(fullGraph: ViewerGraph, hiddenNodes: HiddenElements): Unit =
      val visibleSubGraph: ViewerGraph = fullGraph.removeElements(hiddenNodes)
      val relatedSubGraph: ViewerGraph = selector(visibleSubGraph, selection.now())
      // Corrected: relatedSubGraph.allArrowIds selects the correct arrowIds
      selection.add(relatedSubGraph.nodeIds ++ relatedSubGraph.arrowIds)

    def editSelectedLabel(): Unit =
      val current = now()
      if current.size == 1 then
        editingElementV.set(Some(current.head))

    def clearEditing(): Unit =
      editingElementV.set(None)

    def now(): Selection = selectionV.now()

    def size(): Int = now().size

    def toggle(ss: ElementId*): Unit = selectionV.update(ss.foldLeft(_)(_.toggle(_)))

    def set(ss: Selection)(using name: sourcecode.FullName): Unit =
      selectionV.set(ss)

    @targetName("setElementIds")
    def set1(ss: Set[? <: ElementId])(using name: sourcecode.FullName): Unit =
      set(ElementIds(ss))

    def set2(ss: ElementId*)(using name: sourcecode.FullName): Unit =
      set1(ss.toSet)

    @targetName("addElementIds")
    def add(ss: Set[? <: ElementId]): Unit =
      add(ElementIds(ss))

    def add(ss: Selection): Unit =
      val current  = now()
      val newNodes = ss -- current
      if newNodes.nonEmpty then set(current ++ newNodes)

    def remove(ss: Selection): Unit =
      val current       = now()
      val nodesToRemove = ss intersect current
      if nodesToRemove.nonEmpty then set(current -- nodesToRemove)

    def clear()(using name: sourcecode.FullName): Unit =
      set(ElementIds())

    def contains(id: ElementId) =
      signal.map(ids => id in ids)

    def hide() =
      project.hiddenElements.update(_ ++ selection.now())

    private def visibleGraphNow(): ViewerGraph = phases.visibleGraph.observe.now()

    def selectGroupMembers() =
      val s          = now()
      val classified = s.classify

      // If we have clusters/groups in the selection, find their members
      if classified.groups.nonEmpty then
        val groupIds          = classified.groups
        val fullGraphSnapshot = fullGraphNow()

        // Get all node ids that are members of the selected groups
        val memberNodeIds = fullGraphSnapshot.getAllChildren(groupIds)

        // Keep the original groups/clusters in the selection and add all members
        set(s ++ memberNodeIds)

    def selectSuccessors() =
      _selectSuccessors(fullGraphNow(), hiddenElements.now())

    def selectPredecessors() =
      _selectPredecessors(fullGraphNow(), hiddenElements.now())

    def selectDirectSuccessors() =
      _selectDirectSuccessors(fullGraphNow(), hiddenElements.now())

    def selectDirectPredecessors() =
      _selectDirectPredecessors(fullGraphNow(), hiddenElements.now())

    def addToGroup() =
      val classified = now().classify
      for groupNodeId <- classified.groups.headOption do
        phases.fullGraphV.update(_.moveToGroup(groupNodeId, classified.nodes.toSeq))

    def group() =
      createGroupMaybePrompt(now())

    def ungroup() =
      phases.fullGraphV.update(_.ungroupSelection(now()))

    def combineIntoRecord() =
      val currentSelection = now()
      if currentSelection.nodeIds.nonEmpty then
        phases.fullGraphV.update { graph =>
          val newGraph = graph.combineIntoRecord(currentSelection.nodeIds)
          // Select the newly created record node (it should be the newest node)
          val newNodeIds = newGraph.nodeIds -- graph.nodeIds
          if newNodeIds.nonEmpty then
            set1(newNodeIds)
          newGraph
        }

    def splitRecord() =
      val currentSelection = now()
      if currentSelection.nodeIds.size == 1 then
        val nodeId = currentSelection.nodeIds.head
        phases.fullGraphV.update { graph =>
          if graph.isRecordNode(nodeId) then
            val newGraph = graph.splitRecordNode(nodeId)
            // Select the newly created nodes
            val newNodeIds = newGraph.nodeIds -- graph.nodeIds
            if newNodeIds.nonEmpty then
              set1(newNodeIds)
            newGraph
          else
            graph
        }

    def transposeRecord() =
      val currentSelection = now()
      if currentSelection.nodeIds.size == 1 then
        val nodeId = currentSelection.nodeIds.head
        phases.fullGraphV.update(_.transposeRecord(nodeId))

    def extractFirstRecordCell() =
      val currentSelection = now()
      if currentSelection.nodeIds.size == 1 then
        val nodeId = currentSelection.nodeIds.head
        phases.fullGraphV.update { graph =>
          if graph.isRecordNode(nodeId) then
            val newGraph = graph.extractRecordCell(nodeId, "f0")
            // Select the newly created node
            val newNodeIds = newGraph.nodeIds -- graph.nodeIds
            if newNodeIds.nonEmpty then
              set1(newNodeIds)
            newGraph
          else
            graph
        }

    def selectWholeRecord() =
      val currentSelection = now()
      val newSelection = currentSelection.ids.map {
        case RecordCellId(nodeId, _) => nodeId
        case other                   => other
      }
      set1(newSelection)

    def extractLastRecordCell() =
      val currentSelection = now()
      if currentSelection.nodeIds.size == 1 then
        val nodeId = currentSelection.nodeIds.head
        phases.fullGraphV.update { graph =>
          if graph.isRecordNode(nodeId) then
            graph.getNode(nodeId) match
              case Some(node) =>
                val label = node.label.toString
                // Parse label to determine number of fields
                val cleanLabel = if label.startsWith("{") && label.endsWith("}") then
                  label.substring(1, label.length - 1)
                else
                  label
                val fieldCount = cleanLabel.split(" \\| ").length
                if fieldCount > 0 then
                  val lastPort = s"f${fieldCount - 1}"
                  val newGraph = graph.extractRecordCell(nodeId, lastPort)
                  // Select the newly created node
                  val newNodeIds = newGraph.nodeIds -- graph.nodeIds
                  if newNodeIds.nonEmpty then
                    set1(newNodeIds)
                  newGraph
                else
                  graph
              case None => graph
          else
            graph
        }

    def reverseArrowsStyle() =
      phases.fullGraphV.update(_.reverseArrowsStyle(now()))

    def reverseArrows() =
      phases.fullGraphV.update(_.reverseArrows(now()))

    def selectAllVisibleNodes() =
      val visibleNodes = visibleGraphNow().nodeIds
      set1(visibleNodes)

    def selectAllVisibleArrows() =
      set1(visibleGraphNow().arrowIds)

    def selectAllVisibleGroups() =
      set1(visibleGraphNow().groupIds)

    def selectAll() =
      val visibleGraph = visibleGraphNow()
      val nodes        = visibleGraph.nodeIds
      val edges        = visibleGraph.arrowIds
      val groups       = visibleGraph.groupIds
      set1(nodes ++ edges ++ groups)

    def deleteSelection() =
      phases.fullGraphV.update(_.removeElements(now()))

    /** Duplicates the currently selected nodes, arrows, and groups. Creates new elements with the same attributes as the selected ones.
      * Nodes are placed in the corresponding duplicated group if their original group was also selected. Arrows are duplicated connecting
      * the corresponding (potentially new) nodes. The newly created elements become the selected elements after duplication.
      */
    def duplicateSelection() =
      phases.fullGraphV.update: graph =>
        val currentSelection = now()
        if currentSelection.isEmpty then
          graph
        else
          val (newGraph, newElements) = graph.duplicateSelection(currentSelection.classify)
          if newElements.nonEmpty then
            set1(newElements)
          newGraph

    /** Removes all attributes except 'label' from the selected elements.
      */
    def resetAttributes(): Unit =
      val selection = now()
      if selection.nonEmpty then
        phases.fullGraphV.update(_.resetAttributes(selection))

    /** Resets the layout-related attributes for the selected elements. Placeholder implementation.
      */
    def resetLayout(): Unit =
      // TODO: Implement layout reset logic
      // Could involve removing attributes like pos, width, height, bb?
      println("resetLayout called for selection: " + now())

    def updateSelectionStatus(elementId: ElementId)(shiftKey: Boolean) =
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

    def selectExtendSelectionOverlappingElements(
        rect:                MouseActionRect,
        selectableElements:  Seq[SelectableElement],
        elementsFromRectEnd: js.Array[dom.Element],
        mouseEvent:          Option[dom.MouseEvent] = None
    ) =
      if rect.isEmpty then
        // Equivalent to an onClick event
        findClosestElementId(elementsFromRectEnd, mouseEvent = mouseEvent) match
          case Some(end) => updateSelectionStatus(end)(rect.shift)
          case None      => clear()
      else
        val nodesInRect = selectableElements.filter(isNodeInRect(_, rect)).map(_.elementId).toSet
        if nodesInRect.nonEmpty then
          if rect.shift then
            add(nodesInRect)
          else
            set1(nodesInRect)
        else if !rect.shift then
          clear()

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
      *   1. Gets the element's bounding box in client coordinates 2. Normalizes the selection rect coordinates to handle any direction of
      *      dragging 3. Uses a standard rectangle intersection test
      */
    def isNodeInRect(elem: SelectableElement, rect: MouseActionRect): Boolean =
      val bbox   = elem.ref.getBoundingClientRect()
      val x      = rect.start.x min rect.end.x
      val y      = rect.start.y min rect.end.y
      val width  = math.abs(rect.end.x - rect.start.x)
      val height = math.abs(rect.end.y - rect.start.y)
      !(bbox.right < x ||
        bbox.left > x + width ||
        bbox.bottom < y ||
        bbox.top > y + height)

  def printSelectionToConsole(): Unit =
    // Don't remove this line!! it IS the actual functionality
    pprint.log(selection.now())
    dom.console.log("Visible current selection to the console")

end DiagramSelectionOps

object DiagramSelectionOps:
  /** Finds the element ID at the given selection rectangle's end point.
    * For record nodes, detects which cell was clicked and returns a RecordCellId.
    */
  def findClosestElementId(
      elements: js.Array[dom.Element],
      selector: String = "g.node, g.edge, g.cluster",
      mouseEvent: Option[dom.MouseEvent] = None
  ): Option[ElementId] =
    elements
      .filter(_.namespaceURI == "http://www.w3.org/2000/svg")
      .flatMap(element => Option(element.closest(selector)))
      .distinct
      .collect:
        case g: dom.svg.G => g
      .map { g =>
        SelectableElement.fromDomElement(g) match
          case Some(nodeElem: NodeElement) if nodeElem.isRecordNode && mouseEvent.isDefined =>
            // For record nodes, determine which cell was clicked
            val event = mouseEvent.get
            nodeElem.getCellAtPosition(event.clientX, event.clientY) match
              case Some(cellPort) => RecordCellElement(g, nodeElem.elementId.asInstanceOf[NodeId], cellPort)
              case None           => nodeElem
          case Some(elem) => elem
          case None       => null
      }
      .filter(_ != null)
      .map(_.elementId)
      .headOption

end DiagramSelectionOps
