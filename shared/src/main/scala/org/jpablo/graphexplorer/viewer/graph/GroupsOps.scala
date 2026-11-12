package org.jpablo.graphexplorer.viewer.graph

import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Label
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.ViewerGroup.group

import scala.annotation.tailrec

trait GroupsOps:
  this: ViewerGraph =>

  def moveToGroup(groupId: GroupId, nodeIds: Seq[NodeId]): ViewerGraph =
    modifyMemberships.using(_ ++ nodeIds.map(_ -> groupId))

  def moveToNewGroup(label: String, elementIDS: ElementId*): ViewerGraph =
    moveToNewGroup(ElementIds.from(elementIDS*), label)

  /** Creates a new group containing the specified nodes.
    *
    * Creates a new group with the given label and moves the specified nodes into it. Any nodes that were previously in other groups will be
    * moved to this new group.
    *
    * Then it removes each former parent that this move empties, and each ancestor that becomes empty because of that removal. A group
    * that was empty before the move stays.
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
    // Only the outermost selected elements go into the new group. An element
    // whose ancestor is also selected stays in its group, and moves with that
    // ancestor. If it went into the new group too, the new group went into
    // the parent of that element, which is in the new group: a cycle. Then
    // the DOT writer found no top-level group for the cycle.
    val memberIds = outermost(elementIds.memberIds)
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

      // Only a former parent of a moved element can become empty because of this move.
      // `removeEmptyGroups()` also removes empty groups that the move did not touch, for example an empty legend.
      graphWithNewGroup.removeEmptyGroupsFrom(parents)

  /** The elements of `members` that have no ancestor in `members`. A cycle in
    * the memberships stops the walk (see [[ancestorsOf]]). Thus a group on a
    * cycle is its own ancestor, and it is not outermost.
    */
  private[graph] def outermost[M <: GroupMemberId](members: Set[M]): Set[M] =
    val asMembers = members.toSet[GroupMemberId]
    members.filterNot(member => ancestorsOf(member).exists(asMembers.contains))

  /** The groups that hold `member`: its parent, the parent of that group, and
    * so on, up to the top level. A cycle in the memberships stops the walk.
    */
  private def ancestorsOf(member: GroupMemberId): Set[GroupId] =
    @tailrec
    def walk(current: Option[GroupId], found: Set[GroupId]): Set[GroupId] =
      current match
        case Some(g) if !found.contains(g) => walk(membership(g), found + g)
        case _                             => found
    walk(membership(member), Set.empty)

  /** Moves each element to the parent of its group. An element in a top-level group goes to the root.
    *
    * Then it removes each group that this ungroup empties, and each ancestor that becomes empty because of that removal. A group that
    * was empty before the ungroup stays, and its membership stays.
    */
  def ungroupSelection(elementIds: ElementIds): ViewerGraph =
    // Each element that is in a group, with that group (its former parent).
    // An element in the root does not move.
    val moved: Set[(GroupMemberId, GroupId)] =
      elementIds.memberIds.flatMap(element => membership(element).map(element -> _))

    val newMemberships = moved.foldLeft(memberships):
      case (acc, (element, parent)) =>
        // Put the element in the grandparent. If the parent is top-level, put the element in the root.
        membership(parent).fold(acc - element)(grandParent => acc + (element -> grandParent))

    // Only a former parent can become empty because of this ungroup.
    // `removeEmptyGroups()` also removes empty groups that the ungroup did not touch.
    modifyMemberships.setTo(newMemberships).removeEmptyGroupsFrom(moved.map(_._2))

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

  /** Removes each group in `candidates` that has no children. Then it does the same for the parents of the removed groups, because they
    * can become empty too.
    *
    * Unlike `removeEmptyGroups()`, it keeps an empty group that is not a candidate and is not a parent of a removed group.
    *
    * @param candidates
    *   The groups that an operation changed, for example the former parents of moved elements
    * @return
    *   Updated ViewerGraph without the candidates that became empty
    */
  private[graph] def removeEmptyGroupsFrom(candidates: Set[GroupId]): ViewerGraph =
    val nonEmptyGroups = memberships.values.toSet
    val emptyGroups    = candidates.filter(g => (g in groups) && !(g in nonEmptyGroups))
    if emptyGroups.isEmpty then this
    else
      // Read the parents before the removal, because the removal deletes the memberships of the empty groups
      val parents = emptyGroups.flatMap(membership)
      modifyElements
        .using(_.copy(groups = groups -- emptyGroups, memberships = memberships -- emptyGroups))
        .removeEmptyGroupsFrom(parents -- emptyGroups)
