package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.state.Var
import com.softwaremill.quicklens.*
import org.jpablo.graphexplorer.viewer.components.Action
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.{Arrow, ElementId, ElementIds, NodeId}
import org.jpablo.graphexplorer.viewer.utils.{ClientPoint, UserActionRect}

import scala.annotation.targetName
import scala.scalajs.js

type Selection = ElementIds

trait DiagramSelectionOps:
  this: ViewerState =>

  private val selectionV: Var[Selection] = Var(ElementIds())

  object selection:
    val signal = selectionV.signal
      .distinct
    //    .tapEach(s => if s.nonEmpty then dom.console.log(s"Selection: $s"))

    val _selectSuccessors         = selectRelated((graph, nodes) => graph.allSuccessorsGraph(nodes.nodeIds))
    val _selectPredecessors       = selectRelated((graph, nodes) => graph.allPredecessorsGraph(nodes.nodeIds))
    val _selectDirectSuccessors   = selectRelated((graph, nodes) => graph.directSuccessorsGraph(nodes.nodeIds))
    val _selectDirectPredecessors = selectRelated((graph, nodes) => graph.directPredecessorsGraph(nodes.nodeIds))

    private def selectRelated(
        selector: (ViewerGraph, Selection) => ViewerGraph
    )(fullGraph: ViewerGraph, hiddenNodes: HiddenElements): Unit =
      val visibleSubGraph: ViewerGraph = fullGraph.removeElements(hiddenNodes)
      val relatedSubGraph: ViewerGraph = selector(visibleSubGraph, selection.now())
      // Incorrect: relatedSubGraph.allArrowIds selects the wrong arrowIds
      selection.add(relatedSubGraph.nodeIds ++ relatedSubGraph.arrowIds)

    def now(): Selection = selectionV.now()

    def size(): Int = now().size

    def toggle(ss: ElementId*): Unit = selectionV.update(ss.foldLeft(_)(_.toggle(_)))

    def set(ss: ElementId*): Unit =
      set(ss.toSet)

    def set(ss: Selection): Unit =
      selectionV.set(ss)

    @targetName("setElementIds")
    def set(ss: Set[? <: ElementId]): Unit =
      set(ElementIds(ss))

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

    def clear(): Unit = set(ElementIds())

    def contains(id: ElementId) =
      signal.map(ids => id in ids)

    def hide() =
      project.hiddenElements.update(_ ++ selection.now())

    private val fullGraphNow: ViewerGraph = sourceFlow.fullGraph.now()
    private val visibleGraphNow = sourceFlow.visibleGraph.observe().now()

    def selectGroupMembers() =
      val s          = now()
      val classified = s.classify

      // If we have clusters/groups in the selection, find their members
      if classified.groups.nonEmpty then
        val groupIds          = classified.groups
        val fullGraphSnapshot = fullGraphNow

        // Get all node ids that are members of the selected groups
        val memberNodeIds = fullGraphSnapshot.getAllChildren(groupIds)

        // Keep the original groups/clusters in the selection and add all members
        set(s ++ memberNodeIds)

    def selectSuccessors() =
      _selectSuccessors(fullGraphNow, hiddenElements.now())

    def selectPredecessors() =
      _selectPredecessors(fullGraphNow, hiddenElements.now())

    def selectDirectSuccessors() =
      _selectDirectSuccessors(fullGraphNow, hiddenElements.now())

    def selectDirectPredecessors() =
      _selectDirectPredecessors(fullGraphNow, hiddenElements.now())

    def addToGroup() =
      val classified = now().classify
      for groupNodeId <- classified.groups.headOption do
        sourceFlow.fullGraphV.update(_.moveToGroup(groupNodeId, classified.nodes.toSeq))

    def group() =
      sourceFlow.fullGraphV.update(_.moveToNewGroup(now()))

    def ungroup() =
      sourceFlow.fullGraphV.update(_.ungroupSelection(now()))

    def selectAllVisibleNodes() =
      val visibleNodes = visibleGraphNow.nodeIds
      set(visibleNodes)

    def selectAllVisibleArrows() =
      set(visibleGraphNow.arrowIds)

    def selectAllVisibleGroups() =
      set(visibleGraphNow.groupIds)

    def selectAll() =
      val visibleGraph = visibleGraphNow
      val nodes        = visibleGraph.nodeIds
      val edges        = visibleGraph.arrowIds
      val groups       = visibleGraph.groupIds
      set(nodes ++ edges ++ groups)

    def deleteSelection() =
      sourceFlow.fullGraphV.update: fullGraph =>
        fullGraph.removeElements(now())

    /** Duplicates the currently selected nodes. Creates new nodes with the same attributes as the selected nodes and places them in the
      * same groups. The newly created nodes become the selected elements after duplication.
      */
    def duplicateSelection() =
      sourceFlow.fullGraphV.update: fullGraph =>
        val s: Selection = now()
        if s.isEmpty then
          fullGraph
        else
          // Filter out any non-node elements (like edges)
          val classified       = s.classify
          val nodesToDuplicate = classified.nodes
          if nodesToDuplicate.isEmpty then
            fullGraph
          else
            // Create a new graph with the duplicated nodes
            val (newGraph, newNodeIds) = nodesToDuplicate.foldLeft((fullGraph, Set.empty[NodeId])) {
              case ((graph, newIds), originalId) =>
                // Get the original node's attributes and group
                val originalNode = graph.getNode(originalId).get // Look into this
                val groupId      = graph.membership(originalId)
                // Create a new node with a random ID
                val (updatedGraph, newNodeId) = graph.addNode(groupId)
                // Update the new node with the original node's attributes
                val finalGraph =
                  updatedGraph.updateAttributes(ElementIds.from(newNodeId), originalNode.attributes.toUpdates)
                // Add the new node ID to our collection
                (finalGraph, newIds + newNodeId)
            }

            // Select the newly created nodes
            set(newNodeIds)
            newGraph

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

    def handleMouseUp(ev: dom.MouseEvent): Unit =
      val lineAction = selectionRectLine.now()
      endSelectionArea()
      endSelectionLine()
      for action <- lineAction do
        val start = action.start
        val sel   = now()
        clear()

        // Check if the mouse release point (not the selection rectangle) is inside the source node's bounding box
        val bbox              = start.get.getBoundingClientRect()
        val mouseReleasePoint = (ev.clientX, ev.clientY)
        val isMouseInsideSourceNode =
          mouseReleasePoint._1 >= bbox.left &&
            mouseReleasePoint._1 <= bbox.right &&
            mouseReleasePoint._2 >= bbox.top &&
            mouseReleasePoint._2 <= bbox.bottom

        if sel.size == 1 && isMouseInsideSourceNode then
          start.nodeId.foreach(nodeId => addArrow(nodeId, nodeId))
        else if sel.size == 2 then
          (sel - start.elementId).head.asNodeId.foreach(end => addArrow(start.nodeId.get, end))

    def startSelectionArea(pos: ClientPoint, shift: Boolean): Unit =
      selectionRectArea.set(Some(Action.Area(UserActionRect(pos, pos, shift))))

    def startSelectionLine(pos: ClientPoint, shift: Boolean, start: SelectableElement): Unit =
      selectionRectLine.set(Some(Action.Line(UserActionRect(pos, pos, shift), start)))

    def updateSelection(pos: ClientPoint, shift: Boolean): Unit =
      Var.update(
        selectionRectArea -> { (area: Option[Action.Area]) => area.map(_.modify(_.rect).using(_.copy(end = pos, shift = shift))) },
        selectionRectLine -> { (line: Option[Action.Line]) => line.map(_.modify(_.rect).using(_.copy(end = pos, shift = shift))) }
      )

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
      *   1. Gets the element's bounding box in client coordinates 2. Normalizes the selection rect coordinates to handle any direction of
      *      dragging 3. Uses a standard rectangle intersection test
      */
    def isNodeInRect(elem: SelectableElement, rect: UserActionRect): Boolean =
      val bbox   = elem.get.getBoundingClientRect()
      val x      = rect.start.x min rect.end.x
      val y      = rect.start.y min rect.end.y
      val width  = math.abs(rect.end.x - rect.start.x)
      val height = math.abs(rect.end.y - rect.start.y)
      !(bbox.right < x ||
        bbox.left > x + width ||
        bbox.bottom < y ||
        bbox.top > y + height)

end DiagramSelectionOps
