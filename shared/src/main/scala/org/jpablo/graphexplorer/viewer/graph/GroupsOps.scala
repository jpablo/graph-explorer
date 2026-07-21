package org.jpablo.graphexplorer.viewer.graph

import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Label
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.ViewerGroup.group

trait GroupsOps:
  this: ViewerGraph =>

  def moveToGroup(groupId: GroupId, nodeIds: Seq[NodeId]): ViewerGraph =
    modifyMemberships.using(_ ++ nodeIds.map(_ -> groupId))

  def moveToNewGroup(label: String, elementIDS: ElementId*): ViewerGraph =
    moveToNewGroup(ElementIds.from(elementIDS*), label)

  /** Creates a new group containing the specified nodes.
    *
    * Creates a new group with the given label and moves the specified nodes into it. Any nodes that were previously in other groups will be
    * moved to this new group. Empty groups that result from moving nodes will be removed.
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
    val memberIds = elementIds.memberIds
    if memberIds.isEmpty then this
    else
      val groupId  = nextGroupId()
      val newGroup = group(groupId, Attributes.of(Label -> label))

      // Find the common parent group if one exists
      // Get all parent group IDs for the elements being moved
      val parents = memberIds.flatMap(membership)

      // Determine the common parent (if any)
      val commonParentId: Option[GroupId] =
        if parents.size == 1 then parents.headOption
        else None // No single common parent (or elements had no parents)

      val updated = memberships ++ memberIds.map(_ -> groupId)
      val graphWithNewGroup = modifyElements.using(
        _.copy(
          groups = groups + (groupId -> newGroup),
          // Add the new group to the common parent if it's not the root
          memberships = commonParentId.fold(updated)(pId => updated + (groupId -> pId))
        )
      )
      
      // Clean up any empty groups that may have resulted from moving elements
      graphWithNewGroup.removeEmptyGroups()

  def ungroupSelection(elementIds: ElementIds): ViewerGraph =
    // For each id, find its current group (parent) and that group's parent (grandparent)
    val newMemberships = elementIds
      .memberIds
      .foldLeft(memberships): (acc, element) =>
        // Only process if not already in root group
        membership(element).fold(acc): parent =>
          // Find the grandparent (parent's parent), defaulting to root if none
          membership(parent).fold(acc - element): grandParent =>
            // Remove current membership and add to grandparent if not root
            acc + (element -> grandParent)
    
    val graphWithUpdatedMemberships = modifyMemberships.setTo(newMemberships)
    
    // Clean up any empty groups that may have resulted from the ungrouping
    graphWithUpdatedMemberships.removeEmptyGroups()

  def getDirectChildren(groupIds: Set[GroupId]): Set[GroupMemberId] =
    memberships.collect { case (memId, gId) if gId in groupIds => memId }.toSet

  def getRootChildren: Set[GroupMemberId] =
    (nodes.keySet ++ groups.keySet) -- memberships.keySet

  /** Returns all children elements within the specified groups, including nested elements. This includes direct children as well as
    * children of any subgroups recursively.
    *
    * @param groupIds
    *   The set of group IDs to retrieve children for
    * @return
    *   A set of all element IDs that are direct or indirect children of the specified groups
    */
  def getAllChildren(groupIds: Set[GroupId]): Set[GroupMemberId] =
    if groupIds.isEmpty then
      Set.empty
    else
      // Get the direct children first
      val directChildren = getDirectChildren(groupIds)

      // Find which of the direct children are groups themselves. Match the actual
      // GroupId subtype instead of reconstructing GroupId(memId.value), which would
      // misclassify a NodeId whose value collides with an existing group id.
      val childGroups = directChildren.collect { case g: GroupId if g in groups => g }

      // Base case: no child groups, return just the direct children
      if childGroups.isEmpty then directChildren
      else
        // Recursive case: combine direct children with all children of child groups
        directChildren ++ getAllChildren(childGroups)

  /** Returns all groups that have no children (neither nodes nor subgroups).
    * Note: Root-level elements (those without any parent group) are not considered as having a parent,
    * so groups are only considered empty if they exist but have no direct children.
    *
    * @return A set of GroupIds that are empty
    */
  def getEmptyGroups(): Set[GroupId] =
    // A group is empty iff no membership points at it; one pass over memberships
    // instead of a getDirectChildren scan per group (removeEmptyGroups recurses).
    groups.keySet -- memberships.values.toSet

  /** Recursively removes all empty groups and their memberships.
    * This method will keep removing empty groups until no more empty groups remain,
    * as removing a group might make its parent group empty.
    *
    * @return Updated ViewerGraph with all empty groups removed
    */
  def removeEmptyGroups(): ViewerGraph =
    val emptyGroups = getEmptyGroups()
    if emptyGroups.isEmpty then
      this
    else
      // Remove empty groups and their memberships, then recurse
      val updatedMemberships = memberships.filterNot((elementId, _) => 
        elementId.asGroupId.exists(_ in emptyGroups)
      )
      val updatedGraph = modifyElements.using(_.copy(
        groups = groups -- emptyGroups,
        memberships = updatedMemberships
      ))
      // Recurse in case removing groups made other groups empty
      updatedGraph.removeEmptyGroups()
