package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.extensions.in
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

  val editingElementV = Var[Option[ElementId]](None)

  object selection:
    val signal = selectionV.signal
      .distinct
    // .tapEach(sel => println(s"[selection] $sel"))

    val editingElement = editingElementV.signal.distinct

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

    private def fullGraphNow: ViewerGraph = phases.fullGraph.now()
    def visibleGraphNow: ViewerGraph      = phases.visibleGraph.observe().now()

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
        phases.fullGraphV.update(_.moveToGroup(groupNodeId, classified.nodes.toSeq))

    def group() =
      phases.fullGraphV.update(_.moveToNewGroup(now()))

    def ungroup() =
      phases.fullGraphV.update(_.ungroupSelection(now()))

    def selectAllVisibleNodes() =
      val visibleNodes = visibleGraphNow.nodeIds
      set1(visibleNodes)

    def selectAllVisibleArrows() =
      set1(visibleGraphNow.arrowIds)

    def selectAllVisibleGroups() =
      set1(visibleGraphNow.groupIds)

    def selectAll() =
      val visibleGraph = visibleGraphNow
      val nodes        = visibleGraph.nodeIds
      val edges        = visibleGraph.arrowIds
      val groups       = visibleGraph.groupIds
      set1(nodes ++ edges ++ groups)

    def deleteSelection() =
      phases.fullGraphV.update: fullGraph =>
        fullGraph.removeElements(now())

    /** Duplicates the currently selected nodes, arrows, and groups. Creates new elements with the same attributes as the selected ones.
      * Nodes are placed in the corresponding duplicated group if their original group was also selected. Arrows are duplicated connecting
      * the corresponding (potentially new) nodes. The newly created elements become the selected elements after duplication.
      */
    def duplicateSelection() =
      phases.fullGraphV.update: initialGraph =>
        val s: Selection = now()
        if s.isEmpty then
          initialGraph
        else
          val classified = s.classify

          // 1. Determine all elements to duplicate (selected + descendants of selected groups)
          val selectedGroupIds = classified.groups
          val descendantMembers =
            if selectedGroupIds.nonEmpty then initialGraph.getAllChildren(selectedGroupIds) else Set.empty[GroupMemberId]
          val descendantGroupIds = descendantMembers.collect { case id: GroupId => id }
          val descendantNodeIds  = descendantMembers.collect { case id: NodeId => id }

          val groupsToDuplicate = selectedGroupIds ++ descendantGroupIds
          val nodesToDuplicate  = classified.nodes ++ descendantNodeIds

          // Identify arrows internal to the duplicated nodes/groups
          val internalArrowsToDuplicate = initialGraph.elements.arrows.values.filter { arrow =>
            nodesToDuplicate.contains(arrow.source) && nodesToDuplicate.contains(arrow.target)
          }.map(_.id).toSet

          // Combine explicitly selected arrows and internal arrows
          val allArrowsToDuplicate = classified.arrows ++ internalArrowsToDuplicate

          // 2. Duplicate Groups and create a map from old GroupId to new GroupId
          val (graphAfterGroups, newGroupIds, groupIdMap) =
            groupsToDuplicate.foldLeft((initialGraph, Set.empty[GroupId], Map.empty[GroupId, GroupId])) {
              case ((currentGraph, createdGroupIds, groupMap), originalGroupId) =>
                // Avoid re-duplicating if already processed (e.g., nested group)
                if groupMap.contains(originalGroupId) then (currentGraph, createdGroupIds, groupMap)
                else
                  currentGraph.elements.groups.get(originalGroupId) match
                    case None => (currentGraph, createdGroupIds, groupMap) // Should not happen for valid IDs
                    case Some(originalGroup) =>
                      val newGroupId            = GroupId(org.jpablo.graphexplorer.viewer.formats.dot.ast.SubGraph.randomId())
                      val newGroup              = ViewerGroup.group(newGroupId, originalGroup.attributes)
                      val originalParentGroupId = currentGraph.membership(originalGroupId)
                      // Use the map to find the NEW parent ID if the original parent was also duplicated
                      val targetParentGroupId = originalParentGroupId.flatMap(groupMap.get).orElse(originalParentGroupId)

                      val updatedElements = currentGraph.elements.copy(
                        groups = currentGraph.elements.groups + (newGroupId -> newGroup),
                        memberships = targetParentGroupId.fold(currentGraph.elements.memberships)(pId =>
                          currentGraph.elements.memberships + (newGroupId -> pId)
                        )
                      )
                      val graphWithNewGroup = currentGraph.copy(elements = updatedElements)
                      (graphWithNewGroup, createdGroupIds + newGroupId, groupMap + (originalGroupId -> newGroupId))
            }

          // 3. Duplicate Nodes and create a map from old NodeId to new NodeId
          val (graphAfterNodes, newNodeIds, nodeIdMap) =
            nodesToDuplicate.foldLeft((graphAfterGroups, Set.empty[NodeId], Map.empty[NodeId, NodeId])) {
              case ((currentGraph, createdNodeIds, nodeMap), originalNodeId) =>
                // Avoid re-duplicating if somehow processed twice (shouldn't happen with sets)
                if nodeMap.contains(originalNodeId) then (currentGraph, createdNodeIds, nodeMap)
                else
                  currentGraph.getNode(originalNodeId) match
                    case None => (currentGraph, createdNodeIds, nodeMap) // Should not happen for valid IDs
                    case Some(originalNode) =>
                      val originalParentGroupId = currentGraph.membership(originalNodeId)
                      // Use the map to find the NEW parent ID if the original parent was also duplicated
                      val targetGroupId                 = originalParentGroupId.flatMap(groupIdMap.get).orElse(originalParentGroupId)
                      val (graphWithNewNode, newNodeId) = currentGraph.addNode(targetGroupId)
                      val finalGraphForNode =
                        graphWithNewNode.updateAttributes(ElementIds.from(newNodeId), originalNode.attributes.toUpdates)
                      (finalGraphForNode, createdNodeIds + newNodeId, nodeMap + (originalNodeId -> newNodeId))
            }

          // 4. Duplicate selected Arrows
          val (finalGraph, newArrowIds) =
            allArrowsToDuplicate.foldLeft((graphAfterNodes, Set.empty[ArrowId])) {
              case ((currentGraph, createdArrowIds), originalArrowId) =>
                // Correctly access the arrow using the elements.arrows map
                initialGraph.elements.arrows.get(originalArrowId) match
                  case None => // Should not happen for valid IDs
                    (currentGraph, createdArrowIds)
                  case Some(originalArrow) =>
                    // Determine the endpoints for the new arrow.
                    // Use the new node ID if the original node was duplicated, otherwise use the original node ID.
                    val newSourceId = nodeIdMap.getOrElse(originalArrow.source, originalArrow.source)
                    val newTargetId = nodeIdMap.getOrElse(originalArrow.target, originalArrow.target)

                    // Check if the target nodes for the new arrow actually exist in the graph after node duplication
                    // Correctly check node existence using elements.nodes.contains
                    if currentGraph.elements.nodes.contains(newSourceId) && currentGraph.elements.nodes.contains(newTargetId) then
                      val (graphWithNewArrow, newArrow) = currentGraph.addArrow(newSourceId, newTargetId)
                      val graphWithAttrs =
                        graphWithNewArrow.updateAttributes(ElementIds.from(newArrow.id), originalArrow.attributes.toUpdates)
                      (graphWithAttrs, createdArrowIds + newArrow.id)
                    else
                      // If either endpoint doesn't exist (e.g., original node wasn't selected and doesn't exist anymore?), skip creating arrow.
                      // This case might need further review depending on desired behavior when duplicating arrows connected to non-existent nodes.
                      (currentGraph, createdArrowIds)
            }

          // 5. Select the newly created elements
          val allNewElementIds: Set[ElementId] = newGroupIds.map(id => id: ElementId) ++ newNodeIds ++ newArrowIds
          if allNewElementIds.nonEmpty then
            set1(allNewElementIds)

          finalGraph

    // --- Attribute Resets ---

    /** Removes all attributes except 'label' from the selected elements.
      */
    def resetAttributes(): Unit =
      val s = now()
      if s.nonEmpty then
        phases.fullGraphV.update: initialGraph =>
          s.ids.foldLeft(initialGraph): (currentGraph, elementId) =>
            val currentAttrs = currentGraph.getAttributesById(elementId)
            // Use toDotAttr to check emptiness - returns List[Attr]
            if currentAttrs.toDotAttr.isEmpty then
              // No attributes to remove
              currentGraph
            else
              // Get keys as Strings from toDotAttr, compare with Label.attrId.value
              val keysToRemove = currentAttrs.toDotAttr.map(_.id).toSet - Label.attrId.value
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

    // --- Click Handlers ---

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

    def selectExtendSelectionOverlappingElements(
        rect:                MouseActionRect,
        selectableElements:  Seq[SelectableElement],
        elementsFromRectEnd: js.Array[dom.Element]
    ) =
      // This is meant to capture a single click.
      if rect.isEmpty then
        findClosestElementId(elementsFromRectEnd) match
          case Some(end) => handleClickOnNode(end)(rect.shift)
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
      .map(SelectableElement.fromDomElement)
      .collectFirst:
        case Some(elem) => elem.elementId

end DiagramSelectionOps
