package org.jpablo.graphexplorer.viewer.graph

import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.formats.dot.ast.{AttrValue, SubGraph}
import org.jpablo.graphexplorer.viewer.models.{Arrow, Attributes, NodeId, ElementId, ViewerGroup, ViewerNode, GroupId}

type Arrows = Map[NodeId, Arrow]
type Memberships = Map[ElementId, GroupId]

case class ViewerGraphData(
    // the graph itself is a group
    rootId: GroupId,
    // arrow endpoints should already be in nodes
    arrows: Arrows,
    // Group elements are tracked in memberships
    groups: Map[GroupId, ViewerGroup],
    nodes: Map[NodeId, ViewerNode],
    // ids not in memberships are assumed to be in the root group (the graph itself)
    memberships: Memberships
):
  assert(rootId in groups, s"Root node $rootId not found in groups: $groups")

  val arrowValues: Iterable[Arrow] = arrows.values
  val arrowsSet: Set[Arrow] = arrowValues.toSet

  def addArrow(source: NodeId, target: NodeId): ViewerGraphData =
    val newSeq = maxArrowSequence(source, target)
    val arrow = Arrow(source, target, seq = newSeq + 1)
    copy(arrows = arrows + (arrow.id -> arrow))

  def addToGroup(nodeId: NodeId, groupId: GroupId): ViewerGraphData =
    copy(memberships = memberships + (nodeId -> groupId))

  def removeEmptyGroups: ViewerGraphData =
    val nonEmptyGroupIds = memberships.values.toSet
    val nonEmptyGroups = groups.view.filterKeys(nonEmptyGroupIds).toMap
    copy(groups = nonEmptyGroups)

  def addToNewGroup(ids: Set[NodeId], label: String = ""): ViewerGraphData =
    val groupId = GroupId(s"cluster_${SubGraph.randomId()}")
    val group = ViewerGroup(groupId, Attributes(Map("label" -> AttrValue(label))))
    val groupElements = ids.map(_ -> groupId)
    copy(
      groups = groups + (groupId -> group),
      // This will overwrite any existing memberships, effectively moving the nodes to the new group
      memberships = memberships ++ groupElements
    ).removeEmptyGroups

  def addNode(nodeId: NodeId, label: String = "", groupId: Option[GroupId] = None): ViewerGraphData =
    copy(
      nodes       = nodes + (nodeId -> ViewerNode(nodeId, Attributes(Map("label" -> AttrValue(label))))),
      memberships = groupId.fold(memberships)(g => memberships + (nodeId -> g))
    )

  val root: ViewerGroup =
    groups(rootId)

  val nodesSet = nodes.values.toSet

  def removeNodes(ids: Set[NodeId]): ViewerGraphData =
    copy(
      nodes  = nodes -- ids,
      arrows = arrows -- arrows.keys.filter(id => ids.contains(arrows(id).source) || ids.contains(arrows(id).target)),
      memberships = memberships -- ids
    ).removeEmptyGroups

  def arrowSequences(source: NodeId, target: NodeId): List[Int] =
    arrowValues
      .filter(a => a.source == source && a.target == target)
      .map(_.seq)
      .toList

  def maxArrowSequence(source: NodeId, target: NodeId): Int =
    val seqs = arrowSequences(source, target)
    if seqs.isEmpty then 0 else seqs.max

  def updateAttributes(idsToUpdate: Set[NodeId], attrs: Attributes): ViewerGraphData =
    val (arrowIdsToUpdate, nodeIdsToUpdate) = idsToUpdate.partition(Arrow.isArrowId)

    val arrowsToUpdate: Arrows = arrows.filter((id, _) => id in arrowIdsToUpdate)
    val updatedArrows: Arrows = arrowsToUpdate.transform((_, a) => a.mergeAttrs(attrs))

    val endpointIdsToUpdate: Set[NodeId] = arrowsToUpdate.values.flatMap(_.endpoints).toSet & idsToUpdate

    val allNodeIdsToUpdate: Set[NodeId] = nodeIdsToUpdate ++ endpointIdsToUpdate

    val updatedNodes =
      allNodeIdsToUpdate.foldLeft(nodes): (nodesMap, nodeId) =>
        nodesMap
          .updatedWith(nodeId):
            _.fold(Some(ViewerNode(nodeId, attrs)))(n => Some(n.mergeAttrs(attrs)))

    copy(
      arrows = arrows ++ updatedArrows,
      nodes  = updatedNodes
    )

end ViewerGraphData
