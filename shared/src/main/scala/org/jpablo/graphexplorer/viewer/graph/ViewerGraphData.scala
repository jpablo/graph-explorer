package org.jpablo.graphexplorer.viewer.graph

import com.softwaremill.quicklens.*
import org.jpablo.graphexplorer.viewer.components.attributes.style.StyleSubAttributes
import org.jpablo.graphexplorer.viewer.components.attributes.style.StyleSubAttributes.{
  fromSubAttributes,
  subAttributeIds
}
import org.jpablo.graphexplorer.viewer.extensions.{in, notIn}
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.{NodeStyle, Style}
import org.jpablo.graphexplorer.viewer.formats.dot.ast.{AttrValue, FlattenedGraphElement, SubGraph}
import org.jpablo.graphexplorer.viewer.models.*

case class ViewerGraphData(
    // the graph itself is a group
    rootId: GroupId = defaultRootId,
    nodes:  Map[NodeId, ViewerNode] = Map.empty,
    // arrow endpoints should already be in nodes
    arrows: Map[ArrowId, Arrow] = Map.empty,
    // membership to the root group is implicit
    // i.e. Map(id -> rootId) is not included and would be redundant.
    // i.e. if an element is not in memberships, it belongs to the root group
    memberships: Map[ElementId, GroupId] = Map.empty,
    // The root group does appear here (defaults for nodes and edges).
    groups: Map[GroupId, ViewerGroup] = Map(defaultRootId -> ViewerGroup(defaultRootId))
):
  // groups(rootId) contains the defaults for nodes and edges
  // strictly speaking it is not needed, but it is convenient, so let's enforce it.
  assume(rootId in groups, s"Root node $rootId not found in groups: $groups")
  assume(rootId notIn memberships.values.toSet, "Root group should not be in memberships, it is implicit")
  assume(arrows.values.forall(a => (a.source in nodes) && (a.target in nodes)), "Arrow endpoints not found in nodes")

  val root: ViewerGroup = groups(rootId)

  val arrowValues: Iterable[Arrow] = arrows.values
  val arrowsSet: Set[Arrow] = arrowValues.toSet
  val nodesSet = nodes.values.toSet

  def membership(id: ElementId): Option[GroupId] =
    memberships.get(id)

  def expandStyleAttributes: ViewerGraphData =
    copy(
      groups = groups.transform { (id, g) =>
        g.copy(
          attributes = expandElementAttributes(id, g.attributes),
          edgeAttrs  = expandElementAttributes(id, g.edgeAttrs),
          nodeAttrs  = expandElementAttributes(id, g.nodeAttrs)
        )
      },
      nodes = nodes.transform((id, n) => n.copy(attributes = expandElementAttributes(id, n.attributes)))
    )

  // DOT -> ViewerGraph
  // style="..." -> [fillStyle, boldStyle, invisibleStyle, borderStyle, cornerStyle]
  private def expandElementAttributes(id: ElementId, attrs: Attributes): Attributes =
    attrs.get(NodeStyle.attrId).fold(attrs): styleAttr =>
      // replace the "style" attribute with its sub-attributes (fill, bold, etc.)
      attrs - NodeStyle.attrId ++ StyleSubAttributes.parse(styleAttr).withDefaults.toSubAttributes

  def combineStyleAttributes: ViewerGraphData =
    copy(
      groups = groups.transform { (id, g) =>
        g.copy(
          attributes = combineElementAttributes(id, g.attributes),
          edgeAttrs  = combineElementAttributes(id, g.edgeAttrs),
          nodeAttrs  = combineElementAttributes(id, g.nodeAttrs)
        )
      },
      nodes = nodes.transform { (id, n) =>
        n.copy(
          attributes = combineElementAttributes(id, n.attributes, globals = Some(root.nodeAttrs))
        )
      }
    )

  // ViewerGraph -> DOT
  // Replace the sub-attributes with the combined "style" attribute
  // [fillStyle, boldStyle, invisibleStyle, borderStyle, cornerStyle] -> style="..."
  private def combineElementAttributes(
      id:      ElementId,
      attrs:   Attributes,
      globals: Option[Attributes] = None
  ): Attributes =
    val localSubAttrs = fromSubAttributes(attrs)
    val filteredAttrs = attrs -- subAttributeIds

    val styleStringO =
      globals match
        case None =>
          val styleString = localSubAttrs.toStyleStringSimple
          if styleString.isEmpty then None else Some(styleString)
        case Some(globalAttrs) =>
          localSubAttrs.toStyleCombined(fromSubAttributes(globalAttrs))

    styleStringO match
      case None        => filteredAttrs // remove the style attribute
      case Some(style) => filteredAttrs + (Style.attrId -> AttrValue(style))

  def getDirectChildren(groupIds: Set[GroupId]): Set[ElementId] =
    // For elements with explicit membership
    val explicitChildren = memberships.collect {
      case (elementId, parentId) if parentId in groupIds => elementId
    }.toSet

    // If this is the root group, also include elements that don't have explicit membership
    // (as they default to the root group)
    if groupIds.contains(rootId) then
      val allNodeIds = nodes.keySet
      val allGroupIds = groups.keySet - rootId // Exclude the root group itself
      val allElementIds = allNodeIds ++ allGroupIds
      val elementsWithExplicitMembership = memberships.keySet

      explicitChildren ++ (allElementIds -- elementsWithExplicitMembership)
    else
      explicitChildren

  /** Returns all children elements within the specified groups, including nested elements. This includes direct
    * children as well as children of any subgroups recursively.
    *
    * @param groupIds
    *   The set of group IDs to retrieve children for
    * @return
    *   A set of all element IDs that are direct or indirect children of the specified groups
    */
  def getAllChildren(groupIds: Set[GroupId]): Set[ElementId] =
    // Get the direct children first
    val directChildren = getDirectChildren(groupIds)

    // Find which of the direct children are groups themselves
    val childGroups = directChildren.collect {
      case elementId if GroupId(elementId.value) in groups => GroupId(elementId.value)
    }

    // Base case: no child groups, return just the direct children
    if childGroups.isEmpty then directChildren
    else
      // Recursive case: combine direct children with all children of child groups
      directChildren ++ getAllChildren(childGroups)

  def addArrow(source: NodeId, target: NodeId): (ViewerGraphData, Arrow) =
    val newSeq = maxArrowSequence(source, target)
    val arrow = Arrow(source, target, seq = newSeq + 1)
    (copy(arrows = arrows + (arrow.id -> arrow)), arrow)

  def addToGroup(groupId: GroupId, nodeIds: Seq[NodeId]): ViewerGraphData =
    copy(memberships = memberships ++ nodeIds.map(_ -> groupId))

  def ungroup(elementIds: ElementIds): ViewerGraphData =
    // For each id, find its current group (parent) and that group's parent (grandparent)
    val newMemberships = elementIds.ids.foldLeft(memberships): (mems, element) =>
      // Only process if not already in root group
      membership(element).fold(mems): parent =>
        // Find the grandparent (parent's parent), defaulting to root if none
        membership(parent).fold(mems - element): grandParent =>
          // Remove current membership and add to grandparent if not root
          mems + (element -> grandParent)
    copy(memberships = newMemberships)

  def removeEmptyGroups: ViewerGraphData =
    // the root group is not added to memberships, so it will appear empty
    val nonEmptyGroupIds = memberships.values.toSet + rootId
    val nonEmptyGroups = groups.view.filterKeys(nonEmptyGroupIds).toMap
    copy(groups = nonEmptyGroups)

  def addToNewGroup(elementIds: ElementIds, label: String = ""): ViewerGraphData =
    // Filter out any edge IDs, keep nodes and groups (clusters)
    val nodesOrGroups = elementIds.ids.filter(id => id.isNodeId || id.isGroupId)
    if nodesOrGroups.isEmpty then this
    else
      val groupId = GroupId(s"cluster_${SubGraph.randomId()}")
      val group = ViewerGroup(groupId, Attributes(Map(AttributeId("label") -> AttrValue(label))))

      // Find the common parent group if one exists
      val parentGroupId =
        nodesOrGroups.flatMap(membership)
          .reduceOption((g1, g2) => if g1 == g2 then g1 else rootId)
          .getOrElse(rootId)

      val updated = memberships ++ nodesOrGroups.map(_ -> groupId)
      copy(
        groups = groups + (groupId -> group),
        // Add the new group to the common parent if it's not the root
        memberships = if parentGroupId == rootId then updated else updated + (groupId -> parentGroupId)
      )

  def addNode(nodeId: NodeId, groupId: Option[GroupId] = None, label: String = ""): ViewerGraphData =
    copy(
      nodes = nodes + (nodeId -> ViewerNode(nodeId, Attributes(Map(AttributeId("label") -> AttrValue(label))))),
      memberships = groupId.fold(memberships)(g => memberships + (nodeId -> g))
    )

  def removeElements(elementIds: ElementIds): ViewerGraphData =
    val classified = elementIds.classify
    val groupIdsToRemove = classified.clusters

    val updatedMemberships = memberships.flatMap: (elementId, groupId) =>
      // case 1: remove a nested group
      if elementId.asGroupId.exists(_ in groupIdsToRemove) then
        None
      // case 2: remove a node from a group
      else if groupId in groupIdsToRemove then
        // If group is deleted, add element to group's container if it exists
        memberships.get(groupId).map(containerId => elementId -> containerId)
      else
        Some(elementId -> groupId) // Keep unchanged

    val nodeIdsToRemove = classified.nodes
    val arrowIdsToRemove = classified.arrows

    val updatedArrows = arrows.filterNot { (arrowId, arrow) =>
      (arrowId in arrowIdsToRemove) || (arrow.source in nodeIdsToRemove) || (arrow.target in nodeIdsToRemove)
    }

    copy(
      arrows      = updatedArrows,
      groups      = groups -- groupIdsToRemove,
      nodes       = nodes -- nodeIdsToRemove,
      memberships = updatedMemberships
    ) // .removeEmptyGroups

  def arrowSequences(source: NodeId, target: NodeId): List[Int] =
    arrowValues
      .filter(a => a.source == source && a.target == target)
      .map(_.seq)
      .toList

  def maxArrowSequence(source: NodeId, target: NodeId): Int =
    val seqs = arrowSequences(source, target)
    if seqs.isEmpty then 0 else seqs.max

  /** Updates attributes for a set of nodes and arrows.
    *
    * This method updates the attributes of the specified nodes and arrows:
    *   1. Separates the input IDs into arrow IDs and node IDs 2. Updates attributes for matching arrows 3. Updates
    *      attributes for matching nodes, including any endpoints of updated arrows that were in the original selection
    *
    * @return
    *   Updated ViewerGraphData with the new attributes applied
    */
  def updateAttributes(ids: ElementIds, updates: AttributesUpdates): ViewerGraphData =
    val classified = ids.classify

    val updatedArrows = arrows.view
      .filterKeys(arrowId => arrowId in classified.arrows)
      .mapValues(_.modify(_.attributes).using(updates.applyUpdatesTo))
      .toMap

    val clusterIds = classified.clusters.map(id => GroupId(id.value))

    val updatedClusters = groups.view
      .filterKeys(groupId => groupId in clusterIds)
      .mapValues(_.modify(_.attributes).using(updates.applyUpdatesTo))
      .toMap

    val nodeIdsToUpdate = classified.nodes ++
      (updatedArrows.values.flatMap(_.endpoints).toSet & classified.nodes)

    val updatedNodes = nodeIdsToUpdate.foldLeft(nodes): (nodes, id) =>
      nodes.updated(
        id,
        nodes.getOrElse(id, ViewerNode(id)).modify(_.attributes).using(updates.applyUpdatesTo)
      )

    copy(
      arrows = arrows ++ updatedArrows,
      nodes  = updatedNodes,
      groups = groups ++ updatedClusters
    )

end ViewerGraphData

object ViewerGraphData:

  val defaultRootId = GroupId("G")

  def from(data: FlattenedGraphElement) =
    val arrowEndpoints = data.arrows.flatMap(_.endpoints).toSet
    val nodesMap = data.nodes.map(n => n.id -> n).toMap
    val implicitNodeIds = arrowEndpoints -- nodesMap.keySet

    ViewerGraphData(
      rootId      = data.rootId,
      nodes       = nodesMap ++ implicitNodeIds.map(n => n -> ViewerNode(n)),
      arrows      = data.arrows.map(a => a.id -> a).toMap,
      memberships = data.memberships.toMap, // This messes up with the order of elements
      groups      = data.groups.map(g => g.id -> g).toMap
    )

  val minimal = ViewerGraphData()

end ViewerGraphData
