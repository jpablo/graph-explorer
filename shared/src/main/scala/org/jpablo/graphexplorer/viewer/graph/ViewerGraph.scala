package org.jpablo.graphexplorer.viewer.graph

import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{ArrowHead, ArrowTail, DotAttribute, GraphType}
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph.numberToLetterId
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.ViewerNode.nodeWithDefaults
import org.jpablo.graphexplorer.viewer.state.HiddenElements

import scala.annotation.tailrec

/** Represents a graph that can be visualized in the viewer.
  */
case class ViewerGraph(
    elements:     ViewerGraphElements = ViewerGraphElements.minimal,
    id:           String = ViewerGraphElements.defaultRootId.value,
    tpe:          GraphType = GraphType.default,
    nodeCounter:  Int = 0,
    groupCounter: Int = 0
) extends AttributesOps, TraversalOps, GroupsOps, CombineNodesOps derives CanEqual:

  // --- mutable stuff ----
  private var _nodeCounter  = nodeCounter
  private var _groupCounter = groupCounter

  private[graph] def nextNodeId(): NodeId =
    @tailrec
    def nextAvailable(): NodeId =
      _nodeCounter += 1
      val id = NodeId(numberToLetterId(_nodeCounter))
      if id in nodes then nextAvailable() else id
    nextAvailable()

  private[graph] def nextGroupId(): GroupId =
    @tailrec
    def nextAvailable(): GroupId =
      _groupCounter += 1
      val id = GroupId(s"g${_groupCounter}")
      if id in groups then nextAvailable() else id
    nextAvailable()
  // --- end mutable stuff ----

//  val rootId    = elements.rootId

  val nodes       = elements.nodes
  val arrows      = elements.arrows
  val groups      = elements.groups
  val memberships = elements.memberships

  val nodeIds  = nodes.keySet
  val arrowIds = arrows.keySet
  val groupIds = groups.keySet

  lazy val nodesSeq = nodes.toSeq
  val arrowsSet     = arrows.values.toSet

  val modifyElements               = this.modify(_.elements)
  protected[graph] val modifyNodes = this.modify(_.elements.nodes)
  protected val modifyArrows       = this.modify(_.elements.arrows)
  protected val modifyMemberships  = this.modify(_.elements.memberships)

  def getNode(id: NodeId): Option[ViewerNode] =
    nodes.get(id)

  def membership(id: GroupMemberId): Option[GroupId] =
    memberships.get(id)

  def summary =
    ViewerGraph.Summary(
      nodes = nodes.size,
      arrows = arrows.size,
      groups = groups.size - 1 // skip root group
    )

  /** allNodeIds that are not in the target of any arrow
    */
  def roots: Set[NodeId] =
    nodeIds -- arrowsSet.map(_.target)

  def toVisibleGraph(hiddenNodes: HiddenElements = ElementIds()) =
    this
      .withoutUnsupportedFeatures
      .removeElements(hiddenNodes)
      .withDefaultTheme

  /** Creates a diagram containing the given symbols and the arrows between them.
    *
    * It ignores groups and memberships.
    */
  def subgraph(ids: Set[NodeId]): ViewerGraph =
    val foundNodes     = nodes.filter((id, _) => id in ids)
    val foundNodeIds   = foundNodes.keySet
    val relevantArrows = arrows.filter((_, a) => (a.source in foundNodeIds) && (a.target in foundNodeIds))
    ViewerGraph(
      ViewerGraphElements(
        nodes = foundNodes,
        arrows = relevantArrows
      )
    )

  def removeElements(elementIds: ElementIds): ViewerGraph =
    val classified       = elementIds.classify
    val groupIdsToRemove = classified.groups
    val nodeIdsToRemove  = classified.nodes
    val arrowIdsToRemove = classified.arrows

    // When a member's group is removed, re-parent to the nearest ANCESTOR group that
    // survives the removal — walking past ancestors that are also being removed.
    // Returning None re-homes the member to the top level (a standalone node), rather
    // than leaving a membership pointing at a deleted group, which made the member
    // vanish from the render (counted as a cluster member of a cluster never emitted).
    @tailrec
    def survivingContainer(groupId: GroupId): Option[GroupId] =
      if !(groupId in groupIdsToRemove) then Some(groupId)
      else
        memberships.get(groupId) match
          case Some(parent) => survivingContainer(parent)
          case None         => None

    val updatedMemberships = memberships.flatMap: (elementId, groupId) =>
      // case 1: remove a nested group
      if elementId.asGroupId.exists(_ in groupIdsToRemove) then
        None
      // case 2: remove a node that is being deleted
      else if elementId.asNodeId.exists(_ in nodeIdsToRemove) then
        None
      // case 3: remove a node from a group
      else if groupId in groupIdsToRemove then
        // Re-parent to the nearest surviving ancestor group (or top level).
        survivingContainer(groupId).map(containerId => elementId -> containerId)
      else
        Some(elementId -> groupId) // Keep unchanged

    val updatedArrows = arrows.filterNot { (arrowId, arrow) =>
      (arrowId in arrowIdsToRemove) || (arrow.source in nodeIdsToRemove) || (arrow.target in nodeIdsToRemove)
    }

    // Same policy as node memberships: drop entries for removed arrows;
    // re-parent to the nearest surviving ancestor when the owning group goes away.
    val updatedArrowMemberships = elements.arrowMemberships.flatMap { (arrowId, groupId) =>
      if !updatedArrows.contains(arrowId) then None
      else if groupId in groupIdsToRemove then
        survivingContainer(groupId).map(containerId => arrowId -> containerId)
      else Some(arrowId -> groupId)
    }

    val graphWithRemovedElements = modifyElements.using(_.copy(
      nodes = nodes -- nodeIdsToRemove,
      arrows = updatedArrows,
      memberships = updatedMemberships,
      arrowMemberships = updatedArrowMemberships,
      groups = groups -- groupIdsToRemove
    ))

    // Clean up any empty groups that may have resulted from the removal
    graphWithRemovedElements.removeEmptyGroups()

  private def maxArrowSequence(source: NodeId, target: NodeId): Int =
    val seqs = arrows.values
      .filter(a => a.source == source && a.target == target)
      .map(_.seq)
      .toList
    if seqs.isEmpty then 0 else seqs.max

  def addArrow(source: NodeId, target: NodeId): (ViewerGraph, Arrow) =
    val newSeq = maxArrowSequence(source, target)
    val arrow  = Arrow(source, target, seq = newSeq + 1)
    (modifyArrows.using(_ + (arrow.id -> arrow)), arrow)

  def addNodeWithId(
      nodeId:     NodeId,
      groupId:    Option[GroupId] = None,
      attributes: Attributes = Attributes.empty
  ): ViewerGraph =
    modifyElements.using(
      _.copy(
        nodes = nodes + (nodeId -> nodeWithDefaults(nodeId, attributes)),
        memberships = groupId.fold(memberships)(g => memberships + (nodeId -> g))
      )
    )

  def addNode(
      groupId:    Option[GroupId] = None,
      attributes: Attributes = Attributes.empty
  ): (ViewerGraph, NodeId) =
    val nodeId = nextNodeId()
    (addNodeWithId(nodeId, groupId, attributes), nodeId)

  def addNodeAndArrowFrom(
      source:     NodeId,
      attributes: Attributes = Attributes.empty
  ): (ViewerGraph, NodeId, ArrowId) =
    val nodeId            = nextNodeId()
    val sourceGroup       = membership(source)
    val (newGraph, arrow) = addNodeWithId(nodeId, sourceGroup, attributes).addArrow(source, nodeId)
    (newGraph, nodeId, arrow.id)

  def addNodeAndArrowTo(
      target:     NodeId,
      attributes: Attributes = Attributes.empty
  ): (ViewerGraph, NodeId, ArrowId) =
    val nodeId            = nextNodeId()
    val targetGroup       = membership(target)
    val (newGraph, arrow) = addNodeWithId(nodeId, targetGroup, attributes).addArrow(nodeId, target)
    (newGraph, nodeId, arrow.id)

  /** Carry an arrow's innermost-cluster ownership (arrowMemberships) across an
    * operation that changes its ArrowId. Without this, an edge declared inside a
    * cluster loses its ownership on move/reverse/combine and is re-serialized at
    * top level, changing fdp/dot layout ("wrong ownership of arrows").
    */
  private def rekeyArrowMembership(oldId: ArrowId, newId: ArrowId): ViewerGraph =
    if oldId == newId then this
    else
      this.modify(_.elements.arrowMemberships).using { am =>
        am.get(oldId) match
          case Some(groupId) => am - oldId + (newId -> groupId)
          case None          => am
      }

  def moveArrowEndpoint(arrowId: ArrowId, newEndpoint: ArrowEndpointId): (ViewerGraph, ArrowId) =
    val arrow = arrows(arrowId)
    val newArrow =
      newEndpoint match
        case ArrowEndpointId.SourceId(id) => arrow.copy(source = id)
        case ArrowEndpointId.TargetId(id) => arrow.copy(target = id)
    val updated = modifyArrows.using(_ + (newArrow.id -> newArrow) - arrowId)
      .rekeyArrowMembership(arrowId, newArrow.id)
    (updated, newArrow.id)

  def effectiveAttributeValue[A](
      dotAttribute: DotAttribute[A],
      attrs:        Attributes
  ): A =
    val value = attrs.get(dotAttribute)
    value
      .flatMap(attrVal => dotAttribute.fromString(attrVal.toString))
      .getOrElse(dotAttribute.default)

  /** Reverses the arrow styles for the specified arrow elements in the graph. The method updates the attributes of the arrows, swapping the
    * styles of their head and tail based on their effective values, while considering the default attributes of the graph.
    *
    * @param elementIds
    *   The IDs of the elements to process. Only ArrowIds within this set will have their head and tail attributes reversed.
    * @return
    *   A new ViewerGraph instance with the specified arrow styles reversed.
    */
  def reverseArrowsStyle(elementIds: ElementIds): ViewerGraph =
    elementIds.classify.arrows.foldLeft(this): (currentGraph, arrowId) =>
      currentGraph.arrows.get(arrowId) match
        case None => currentGraph
        case Some(ogArrow) =>

          val effectiveHead = ogArrow.attributes.getAs(ArrowHead)
          val effectiveTail = ogArrow.attributes.getAs(ArrowTail)

          val defaultHeadIfOmitted = defaultEdgeTheme.getAs(ArrowHead)
          val defaultTailIfOmitted = defaultEdgeTheme.getAs(ArrowTail)

          var updatedAttributes = ogArrow.attributes

          if effectiveTail == defaultHeadIfOmitted then
            updatedAttributes -= ArrowHead.attrId
          else
            updatedAttributes += (ArrowHead -> effectiveTail)

          if effectiveHead == defaultTailIfOmitted then
            updatedAttributes -= ArrowTail.attrId
          else
            updatedAttributes += (ArrowTail -> effectiveHead)
          currentGraph.modifyArrows.using(_ + (arrowId -> ogArrow.copy(attributes = updatedAttributes)))

  /** Reverses the direction of the specified arrows.
    *
    * @param elementIds
    *   The IDs of the elements to process. Only ArrowIds within this set will be reversed.
    * @return
    *   A new ViewerGraph with the specified arrows reversed.
    */
  def reverseArrows(elementIds: ElementIds): ViewerGraph =
    val arrowIdsToReverse = elementIds.classify.arrows

    arrowIdsToReverse.foldLeft(this): (currentGraph, arrowId) =>
      currentGraph.arrows.get(arrowId) match
        case None                => currentGraph // Arrow not found, skip
        case Some(originalArrow) =>
          // 1. Remove the original arrow
          val graphWithoutOriginal = currentGraph.modifyArrows.using(_ - arrowId)
          // 2. Calculate the sequence number for the reversed arrow
          val newSource = originalArrow.target
          val newTarget = originalArrow.source
          val newSeq    = graphWithoutOriginal.maxArrowSequence(newSource, newTarget) + 1
          // 3. Create the reversed arrow
          val reversedArrow = Arrow(newSource, newTarget, attributes = originalArrow.attributes, seq = newSeq)
          // 4. Add the reversed arrow, carrying its cluster ownership across the id change
          graphWithoutOriginal.modifyArrows.using(_ + (reversedArrow.id -> reversedArrow))
            .rekeyArrowMembership(arrowId, reversedArrow.id)

  /** Smart connection behavior for adding nodes.
    *
    * @param selectedElementId
    *   The currently selected element ID (optional)
    * @param attributes
    *   Attributes for the new node
    * @param direction
    *   Direction for arrow creation when connecting to a node
    * @return
    *   A tuple of (updated graph, new node ID, optional arrow ID)
    */
  def addNodeWithSmartConnection(
      selectedElementId: Option[ElementId],
      attributes:        Attributes,
      direction:         ArrowDirection
  ): (ViewerGraph, NodeId, Option[ArrowId]) =
    selectedElementId match
      case None =>
        // No selection: just add a standalone node
        val (newGraph, newNodeId) = addNode(attributes = attributes)
        (newGraph, newNodeId, None)
      case Some(selected) =>
        selected match
          case id: NodeId =>
            // Selected node: add new node and connect with arrow
            val (newGraph, newNodeId, arrowId) = direction match
              case ArrowDirection.forward  => addNodeAndArrowFrom(source = id, attributes = attributes)
              case ArrowDirection.backward => addNodeAndArrowTo(target = id, attributes = attributes)
            (newGraph, newNodeId, Some(arrowId))
          case id: GroupId =>
            // Selected group: add node to that group
            val (newGraph, newNodeId) = addNode(groupId = Some(id), attributes = attributes)
            (newGraph, newNodeId, None)
          case _: ArrowId =>
            // Selected arrow: just add standalone node
            val (newGraph, newNodeId) = addNode(attributes = attributes)
            (newGraph, newNodeId, None)

//  lazy val toTrees: Tree[ViewerNode] =
//    val paths =
//      for ns <- nodes.toList yield (ns.id.toString.split("/").init.toList, ns.label, ns)

  /** Creates a new subdiagram with all the symbols containing the given String.
    */
  def filterByNodeId(str: String): ViewerGraph =
    val ids = nodeIds.filter(_.toString.toLowerCase.contains(str.toLowerCase))
    subgraph(ids)

  def filterArrowsBy(p: Arrow => Boolean) =
    arrowsSet.filter(p)

  /** Duplicates the currently selected nodes, arrows, and groups. Creates new elements with the same attributes as the selected ones. Nodes
    * are placed in the corresponding duplicated group if their original group was also selected. Arrows are duplicated connecting the
    * corresponding (potentially new) nodes. The newly created elements become the selected elements after duplication.
    */
  def duplicateSelection(classified: IdsByKind) =

    // 1. Determine all elements to duplicate (selected + descendants of selected groups)
    val descendantMembers = getAllChildren(classified.groups)
    val descendants       = GroupMemberId.classify(descendantMembers)
    val groupsToDuplicate = classified.groups ++ descendants.groups
    val nodesToDuplicate  = classified.nodes ++ descendants.nodes
    val internalArrowsToDuplicate = arrows
      .values.filter(a => (a.source in nodesToDuplicate) && (a.target in nodesToDuplicate)).map(_.id).toSet
    val allArrowsToDuplicate = classified.arrows ++ internalArrowsToDuplicate

    // 2. Duplicate Groups
    val (graphAfterGroups, newGroupIds, groupIdMap) =
      groupsToDuplicate.foldLeft((this, Set.empty[GroupId], Map.empty[GroupId, GroupId])) {
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
          elements.arrows.get(ogArrowId) match
            case None => acc // Should not happen
            case Some(ogArrow) =>
              duplicateSingleArrow(currentGraph, ogArrow, nodeIdMap) match
                case Some((newGraph, newArrowId)) => (newGraph, createdArrowIds + newArrowId)
                case None                         => acc
      }
    // 5. Select the newly created elements
    val allNewElementIds = newGroupIds.map(id => id: ElementId) ++ newNodeIds ++ newArrowIds

    (finalGraph, allNewElementIds)
  end duplicateSelection

  private def duplicateSingleGroup(
      graph:      ViewerGraph,
      group:      ViewerGroup,
      groupIdMap: Map[GroupId, GroupId] // Needed to find the *new* parent
  ): (ViewerGraph, GroupId) =
    val newGroupId = graph.nextGroupId()
    // Filter out layout-specific attributes that shouldn't be copied
    val filteredAttributes = group.attributes.filterKeys(attrId =>
      !Set("_gvid", "width", "pos", "height", "lp", "lwidth", "lheight").contains(attrId.value)
    )
    val newGroup        = ViewerGroup.group(newGroupId, filteredAttributes)
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
    // Filter out layout-specific attributes that shouldn't be copied
    val filteredAttributes = node.attributes.filterKeys(attrId =>
      !Set("_gvid", "width", "pos", "height").contains(attrId.value)
    )
    val finalGraphForNode = newGraph.updateAttributes(ElementIds.from(newNodeId), filteredAttributes.toUpdates)
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

//  def toCSV: CSV =
//    CSV(
//      arrows
//        .map(a => Array(a.source.toString, a.target.toString))
//        .toArray
//    )
end ViewerGraph

object ViewerGraph:

  /** omitInternal = true is useful when showing the text to the user. omitInternal = false is needed when suing the DOT text to render the
    * graph.
    * @param graph
    *   the ViewerGraph to convert to text
    * @param omitInternal
    *   whether to omit internal attributes and elements
    * @return
    *   the DOT text representation of the graph
    */
  def viewerGraphToText(graph: ViewerGraph, omitInternal: Boolean): String =
    viewerGraphElementsToText(graph.elements.combineStyleAttributes, graph.id, graph.tpe, omitInternal)

  private def numberToLetterId(n: Int): String =
    if n <= 0 then throw IllegalArgumentException("Node ID number must be positive")
    else
      def toBase26(n: Int): List[Int] =
        if n == 0 then Nil
        else
          val adjusted  = n - 1
          val quotient  = adjusted / 26
          val remainder = adjusted % 26
          remainder :: (if quotient > 0 then toBase26(quotient) else Nil)

      toBase26(n).reverse.map(i => (i + 97).toChar).mkString

  def basic(arrows: (NodeId, NodeId)*): ViewerGraph =
    // Assign a per-(source,target) sequence so parallel/duplicate edges get distinct
    // ArrowIds instead of collapsing to one via .toMap (all-seq-1 would dedup).
    val (builtArrows, _) =
      arrows.foldLeft((Vector.empty[Arrow], Map.empty[(NodeId, NodeId), Int])) {
        case ((acc, seqByPair), (a, b)) =>
          val seq = seqByPair.getOrElse((a, b), 0) + 1
          (acc :+ Arrow(a, b, seq = seq), seqByPair.updated((a, b), seq))
      }
    ViewerGraph(
      ViewerGraphElements(arrows = builtArrows.map(a => a.id -> a).toMap)
    )

  val minimal: ViewerGraph = ViewerGraph()

  val minimalWithDirected =
    ViewerGraph(ViewerGraphElements(graphAttributes = Attributes.of("directed" -> "true")))

  case class Summary(
      nodes:  Int,
      arrows: Int,
      groups: Int
  )

end ViewerGraph
