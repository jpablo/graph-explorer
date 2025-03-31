package org.jpablo.graphexplorer.viewer.graph

import org.jpablo.graphexplorer.viewer.extensions.{in, notIn}
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.ViewerGroup.group

case class ViewerGraphElements(
    // the graph itself is a group
    rootId: GroupId = defaultRootId,
    nodes:  Map[NodeId, ViewerNode] = Map.empty,
    // arrow endpoints should already be in nodes
    arrows: Map[ArrowId, Arrow] = Map.empty,
    // membership to the root group is implicit
    // i.e. Map(id -> rootId) is not included and would be redundant.
    // i.e. if an element is not in memberships, it belongs to the root group
    memberships: Map[GroupMemberId, GroupId] = Map.empty,
    // The root group does appear here (defaults for nodes and edges).
    groups: Map[GroupId, ViewerGroup] = Map(initialGroup)
):
  // groups(rootId) contains the defaults for nodes and edges
  // strictly speaking it is not needed, but it is convenient, so let's enforce it.
  assume(rootId in groups, s"Root node $rootId not found in groups: $groups")
  assume(rootId notIn memberships.values.toSet, "Root group should not be in memberships, it is implicit")
  assume(arrows.values.forall(a => (a.source in nodes) && (a.target in nodes)), "Arrow endpoints not found in nodes")

  val rootGroup: ViewerGroup = groups(rootId)
end ViewerGraphElements

object ViewerGraphElements:
  val defaultRootId = GroupId("G")
  val initialGroup  = defaultRootId -> group(defaultRootId)
  val minimal       = ViewerGraphElements()
