package org.jpablo.graphexplorer.viewer.graph

import org.jpablo.graphexplorer.viewer.models.*

import scala.annotation.tailrec
import scala.collection.immutable.VectorMap

case class ViewerGraphElements(
    // the graph itself is a group
//    rootId: GroupId = defaultRootId,
    nodes: VectorMap[NodeId, ViewerNode] = VectorMap.empty,
    // arrow endpoints should already be in nodes
    arrows: Map[ArrowId, Arrow] = Map.empty,
    // membership to the top-level graph is implicit
    // i.e. if an element is not in memberships, it belongs to top-level graph
    memberships: Map[GroupMemberId, GroupId] = Map.empty,
    // The root group does appear here (defaults for nodes and edges).
    groups:                 Map[GroupId, ViewerGroup] = Map.empty,
    graphAttributes:        Attributes = Attributes.empty,
    defaultNodeAttributes:  Attributes = Attributes.empty,
    defaultArrowAttributes: Attributes = Attributes.empty,
    defaultGroupAttributes: Attributes = Attributes.empty
)
// groups(rootId) contains the defaults for nodes and edges
// strictly speaking it is not needed, but it is convenient, so let's enforce it.
//  assume(rootId in groups, s"Root node $rootId not found in groups: $groups")
//  assume(rootId notIn memberships.values.toSet, "Root group should not be in memberships, it is implicit")
//  assume(arrows.values.forall(a => (a.source in nodes) && (a.target in nodes)), "Arrow endpoints not found in nodes")

object ViewerGraphElements:
  val defaultRootId = GroupId("G")
  val minimal       = ViewerGraphElements()

  @tailrec
  def ancestorGroups(
      memberships: Map[GroupMemberId, GroupId],
      currentId:   GroupMemberId,
      ancestors:   List[GroupId]
  ): List[GroupId] =
    memberships.get(currentId) match
      case Some(parentId) => ancestorGroups(memberships, parentId, parentId :: ancestors)
      case None           => ancestors
