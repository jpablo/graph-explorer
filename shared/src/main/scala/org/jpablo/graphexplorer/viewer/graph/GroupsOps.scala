package org.jpablo.graphexplorer.viewer.graph

import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.formats.dot.ast.{AttrValue, SubGraph}
import org.jpablo.graphexplorer.viewer.models.{
  AttributeId,
  Attributes,
  ElementId,
  ElementIds,
  GroupId,
  NodeId,
  ViewerGroup
}

trait GroupsOps:
  this: ViewerGraph =>

  def moveToGroup(groupId: GroupId, nodeIds: Seq[NodeId]): ViewerGraph =
    modifyMemberships.using(_ ++ nodeIds.map(_ -> groupId))

  def moveToNewGroup(label: String, elementIDS: ElementId*): ViewerGraph =
    moveToNewGroup(ElementIds.from(elementIDS*), label)

  /** Creates a new group containing the specified nodes.
    *
    * Creates a new group with the given label and moves the specified nodes into it. Any nodes that were previously in
    * other groups will be moved to this new group. Empty groups that result from moving nodes will be removed.
    *
    * @param ids
    *   Set of node IDs to add to the new group
    * @param label
    *   Optional label for the new group, defaults to empty string
    * @return
    *   Updated ViewerGraph with the new group containing the specified nodes
    */
  def moveToNewGroup(elementIds: ElementIds, label: String = ""): ViewerGraph =
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
      modifyElements.using(
        _.copy(
          groups = groups + (groupId -> group),
          // Add the new group to the common parent if it's not the root
          memberships = if parentGroupId == rootId then updated else updated + (groupId -> parentGroupId)
        )
      )

  def ungroupSelection(elementIds: ElementIds): ViewerGraph =
    // For each id, find its current group (parent) and that group's parent (grandparent)
    val newMemberships = elementIds.ids
      .filter(id => id.isNodeId || id.isGroupId)
      .foldLeft(memberships): (mems, element) =>
        // Only process if not already in root group
        membership(element).fold(mems): parent =>
          // Find the grandparent (parent's parent), defaulting to root if none
          membership(parent).fold(mems - element): grandParent =>
            // Remove current membership and add to grandparent if not root
            mems + (element -> grandParent)
    modifyMemberships.setTo(newMemberships)

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
