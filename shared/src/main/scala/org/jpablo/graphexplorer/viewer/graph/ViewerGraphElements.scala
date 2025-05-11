package org.jpablo.graphexplorer.viewer.graph

import org.jpablo.graphexplorer.viewer.models.*

import scala.annotation.tailrec
import scala.collection.immutable.VectorMap

case class ViewerGraphElements(
    nodes: VectorMap[NodeId, ViewerNode] = VectorMap.empty,
    // arrow endpoints should already be in nodes
    arrows: Map[ArrowId, Arrow] = Map.empty,
    // membership to the top-level graph is implicit
    // i.e. if an element is not in memberships, it belongs to top-level graph
    memberships: Map[GroupMemberId, GroupId] = Map.empty,
    groups:      Map[GroupId, ViewerGroup] = Map.empty,
    //
    graphAttributes:        Attributes = Attributes.empty,
    defaultNodeAttributes:  Attributes = Attributes.empty,
    defaultArrowAttributes: Attributes = Attributes.empty,
    defaultGroupAttributes: Attributes = Attributes.empty
)

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
