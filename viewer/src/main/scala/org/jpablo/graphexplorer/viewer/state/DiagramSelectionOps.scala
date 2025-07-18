package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.EventStream
import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.formats.dot.ast.SubGraph
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Label
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
  private val selectionOptimizer = new SelectionOptimizer()

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
      phases.fullGraphV.update(_.moveToNewGroup(now()))

    def ungroup() =
      phases.fullGraphV.update(_.ungroupSelection(now()))

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
      phases.fullGraphV.update: fullGraph =>
        val selectedElements = now()
        selectionOptimizer.invalidateCache(selectedElements.ids.toSet)
        fullGraph.removeElements(selectedElements)

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
          // Clear cache since we're about to add new elements
          selectionOptimizer.clearCache()
          val classified = currentSelection.classify

          // 1. Determine all elements to duplicate (selected + descendants of selected groups)
          val descendantMembers = graph.getAllChildren(classified.groups)
          val descendants       = GroupMemberId.classify(descendantMembers)
          val groupsToDuplicate = classified.groups ++ descendants.groups
          val nodesToDuplicate  = classified.nodes ++ descendants.nodes
          val internalArrowsToDuplicate = graph.arrows
            .values.filter(a => (a.source in nodesToDuplicate) && (a.target in nodesToDuplicate)).map(_.id).toSet
          val allArrowsToDuplicate = classified.arrows ++ internalArrowsToDuplicate

          // 2. Duplicate Groups
          val (graphAfterGroups, newGroupIds, groupIdMap) =
            groupsToDuplicate.foldLeft((graph, Set.empty[GroupId], Map.empty[GroupId, GroupId])) {
              case (acc @ (currentGraph, createdGroupIds, groupMap), ogGroupId) =>
                if ogGroupId in groupMap then
                  acc // Already duplicated (nested)
                else
                  currentGraph.groups.get(ogGroupId) match
                    case None => acc // Should not happen
                    case Some(ogGroup) =>
                      val (newGraph, newGroupId) = duplicateSingleGroup(currentGraph, ogGroup, groupMap)
                      (newGraph, createdGroupIds + newGroupId, groupMap + (ogGroupId -> newGroupId))
            }
          // 3. Duplicate Nodes
          val (graphAfterNodes, newNodeIds, nodeIdMap) =
            nodesToDuplicate.foldLeft((graphAfterGroups, Set.empty[NodeId], Map.empty[NodeId, NodeId])) {
              case (acc @ (currentGraph, createdNodeIds, nodeMap), ogNodeId) =>
                // Node map check likely redundant due to using Set, but safe
                if ogNodeId in nodeMap then
                  acc
                else
                  currentGraph.getNode(ogNodeId) match
                    case None => acc // Should not happen
                    case Some(ogNode) =>
                      val (newGraph, newNodeId) = duplicateSingleNode(currentGraph, ogNode, groupIdMap)
                      (newGraph, createdNodeIds + newNodeId, nodeMap + (ogNodeId -> newNodeId))
            }
          // 4. Duplicate Arrows
          val (finalGraph, newArrowIds) =
            allArrowsToDuplicate.foldLeft((graphAfterNodes, Set.empty[ArrowId])) {
              case (acc @ (currentGraph, createdArrowIds), ogArrowId) =>
                graph.elements.arrows.get(ogArrowId) match
                  case None => acc // Should not happen
                  case Some(ogArrow) =>
                    duplicateSingleArrow(currentGraph, ogArrow, nodeIdMap) match
                      case Some((newGraph, newArrowId)) => (newGraph, createdArrowIds + newArrowId)
                      case None                         => acc
            }
          // 5. Select the newly created elements
          val allNewElementIds = newGroupIds.map(id => id: ElementId) ++ newNodeIds ++ newArrowIds

          if allNewElementIds.nonEmpty then
            set1(allNewElementIds)

          finalGraph
    end duplicateSelection

    private def duplicateSingleGroup(
        graph:      ViewerGraph,
        group:      ViewerGroup,
        groupIdMap: Map[GroupId, GroupId] // Needed to find the *new* parent
    ): (ViewerGraph, GroupId) =
      val newGroupId      = GroupId(SubGraph.randomId())
      val newGroup        = ViewerGroup.group(newGroupId, group.attributes)
      val ogParentGroupId = graph.membership(group.id)
      // Use the map to find the NEW parent ID if the original parent was also duplicated
      val targetParentGroupId = ogParentGroupId.flatMap(groupIdMap.get).orElse(ogParentGroupId)
      val updatedGraph =
        graph
          .modify(_.elements.groups).using(_ + (newGroupId -> newGroup))
          .modify(_.elements.memberships).using(mbs => targetParentGroupId.fold(mbs)(pId => mbs + (newGroupId -> pId)))

      (updatedGraph, newGroupId)

    private def duplicateSingleNode(
        graph:      ViewerGraph,
        node:       ViewerNode,
        groupIdMap: Map[GroupId, GroupId] // Needed to find the *new* parent group
    ): (ViewerGraph, NodeId) =
      val ogParentGroupId = graph.membership(node.id)
      // Use the map to find the NEW parent ID if the original parent group was also duplicated
      val targetGroupId         = ogParentGroupId.flatMap(groupIdMap.get).orElse(ogParentGroupId)
      val (newGraph, newNodeId) = graph.addNode(targetGroupId)
      val finalGraphForNode     = newGraph.updateAttributes(ElementIds.from(newNodeId), node.attributes.toUpdates)
      (finalGraphForNode, newNodeId)

    private def duplicateSingleArrow(
        graph:     ViewerGraph,
        arrow:     Arrow,
        nodeIdMap: Map[NodeId, NodeId] // Needed to find the *new* endpoints
    ): Option[(ViewerGraph, ArrowId)] =
      // Determine the endpoints for the new arrow.
      // Use the new node ID if the original node was duplicated, otherwise use the original node ID.
      val newSourceId = nodeIdMap.getOrElse(arrow.source, arrow.source)
      val newTargetId = nodeIdMap.getOrElse(arrow.target, arrow.target)
      // Check if the target nodes for the new arrow actually exist in the graph
      if (newSourceId in graph.elements.nodes) && (newTargetId in graph.elements.nodes) then
        val (newGraph, newArrow) = graph.addArrow(newSourceId, newTargetId)
        val graphWithAttrs       = newGraph.updateAttributes(ElementIds.from(newArrow.id), arrow.attributes.toUpdates)
        Some((graphWithAttrs, newArrow.id))
      else
        None

    /** Removes all attributes except 'label' from the selected elements.
      */
    def resetAttributes(): Unit =
      val s = now()
      if s.nonEmpty then
        phases.fullGraphV.update: initialGraph =>
          s.ids.foldLeft(initialGraph): (currentGraph, elementId) =>
            val currentAttrs = currentGraph.getAttributesById(elementId).toDotAttr
            if currentAttrs.isEmpty then
              // No attributes to remove
              currentGraph
            else
              // Get keys as Strings from toDotAttr, compare with Label.attrId.value
              val keysToRemove = currentAttrs.map(_.id).toSet - Label.attrId.value
              if keysToRemove.isEmpty then
                // Only label attribute was present (or attrs were empty), nothing to remove
                currentGraph
              else
                // Convert keys back to AttributeId for the update
                val updateForThisElement = AttributeUpdates.remove(keysToRemove.map(AttributeId(_)))
                // updateAttributes returns a *new* graph, so use it in the next fold step
                currentGraph.updateAttributes(ElementIds.from(elementId), updateForThisElement)

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


    val useOptimizedSelection = false   

    def selectExtendSelectionOverlappingElements(
        rect:                MouseActionRect,
        selectableElements:  Seq[SelectableElement],
        elementsFromRectEnd: js.Array[dom.Element]
    ) =
      if rect.isEmpty then
        // Equivalent to an onClick event
        findClosestElementId(elementsFromRectEnd) match
          case Some(end) => updateSelectionStatus(end)(rect.shift)
          case None      => clear()
      else if useOptimizedSelection then
        // Throttle updates to improve performance during drag operations
        if selectionOptimizer.shouldUpdateSelection() then
          selectionOptimizer.measurePerformance("selection-update"):
            // Use optimized selection with spatial indexing
            selectionOptimizer.buildSpatialIndex(selectableElements)
            val elementsInRect = selectionOptimizer.findElementsInRect(selectableElements, rect)
            val nodesInRect = elementsInRect.map(_.elementId).toSet
            
            if nodesInRect.nonEmpty then
              if rect.shift then
                add(nodesInRect)
              else
                set1(nodesInRect)
            else if !rect.shift then
              clear()
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
  
  def printSelectionPerformanceStats(): Unit =
    val stats = selectionOptimizer.getPerformanceStats()
    dom.console.log("Selection Performance Stats:")
    stats.foreach { case (operation, (avg, max, count)) =>
      dom.console.log(s"  $operation: avg=${avg}ms, max=${max}ms, count=$count")
    }

end DiagramSelectionOps

object DiagramSelectionOps:
  /** Finds the node ID at the given selection rectangle's end point
    */
  def findClosestElementId(
      elements: js.Array[dom.Element],
      selector: String = "g.node, g.edge, g.cluster"
  ): Option[ElementId] =
    elements
      .filter(_.namespaceURI == "http://www.w3.org/2000/svg")
      .flatMap(element => Option(element.closest(selector)))
      .distinct
      .collect:
        case g: dom.svg.G => g
      .map(SelectableElement.fromDomElement)
      .collectFirst:
        case Some(elem) => elem.elementId

end DiagramSelectionOps
