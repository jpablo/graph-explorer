package org.jpablo.graphexplorer.viewer.graph

import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.formats.dot.ast.{AttrValue, FlattenedGraphElement, SubGraph}
import org.jpablo.graphexplorer.viewer.models.{Arrow, Attributes, ElementId, GroupId, NodeId, ViewerGroup, ViewerNode}

type Arrows = Map[NodeId, Arrow]
type Memberships = Map[ElementId, GroupId]

case class ViewerGraphData(
    // the graph itself is a group
    rootId: GroupId,
    // arrow endpoints should already be in nodes
    arrows: Arrows,
    // Group elements are tracked in memberships
    groups: Map[GroupId, ViewerGroup],
    nodes:  Map[NodeId, ViewerNode],
    // ids not in memberships are assumed to be in the root group (the graph itself)
    memberships: Memberships
):
  assert(rootId in groups, s"Root node $rootId not found in groups: $groups")

  val arrowValues: Iterable[Arrow] = arrows.values
  val arrowsSet: Set[Arrow] = arrowValues.toSet

  def getMembership(id: ElementId): GroupId =
    memberships.getOrElse(id, rootId)

  def addArrow(source: NodeId, target: NodeId): (ViewerGraphData, Arrow) =
    val newSeq = maxArrowSequence(source, target)
    val arrow = Arrow(source, target, seq = newSeq + 1)
    (copy(arrows = arrows + (arrow.id -> arrow)), arrow)

  def addToGroup(nodeId: NodeId, groupId: GroupId): ViewerGraphData =
    copy(memberships = memberships + (nodeId -> groupId))

  def removeEmptyGroups: ViewerGraphData =
    // the root group is not added to memberships, so it will appear empty
    val nonEmptyGroupIds = memberships.values.toSet + rootId
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
      nodes = nodes -- ids,
      arrows = arrows.filterNot { case (arrowId, arrow) =>
        ids.contains(arrowId) ||
        ids.contains(arrow.source) ||
        ids.contains(arrow.target)
      },
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

  /** Updates attributes for a set of nodes and arrows.
    *
    * This method updates the attributes of the specified nodes and arrows:
    * 1. Separates the input IDs into arrow IDs and node IDs
    * 2. Updates attributes for matching arrows
    * 3. Updates attributes for matching nodes, including any endpoints of updated arrows
    * that were in the original selection
    *
    * @param idsToUpdate Set of node and arrow IDs to update attributes for
    * @param attrs The new attributes to apply
    * @return Updated ViewerGraphData with the new attributes applied
    */
  def updateAttributes(idsToUpdate: Set[NodeId], attrs: Attributes): ViewerGraphData =
    val (arrowIds, notArrows) = idsToUpdate.partition(NodeId.isArrowId)
    val (clusterIds, nodeIds) = notArrows.partition(NodeId.isClusterId)
    val groupIds = clusterIds.map(id => GroupId(id.value))
    
    val updatedArrows = arrows.view
      .filterKeys(_ in arrowIds)
      .mapValues(_.copy(attrs = attrs))
      .toMap

    val updatedClusters = groups.view
      .filterKeys(_ in groupIds)
      .mapValues(_.copy(attrs = attrs))
      .toMap

    val nodeIdsToUpdate = nodeIds ++ 
      (updatedArrows.values.flatMap(_.endpoints).toSet & idsToUpdate)

    val updatedNodes = nodeIdsToUpdate.foldLeft(nodes) { (nodes, id) =>
      nodes.updated(id, nodes.getOrElse(id, ViewerNode(id)).copy(attrs = attrs))
    }

    copy(
      arrows = arrows ++ updatedArrows,
      nodes = updatedNodes,
      groups = groups ++ updatedClusters
    )
end ViewerGraphData

object ViewerGraphData:
  def from(data: FlattenedGraphElement) =
    val arrowEndpoints = data.arrows.flatMap(_.endpoints).toSet
    val nodesMap = data.nodes.map(n => n.id -> n).toMap
    val implicitNodeIds = arrowEndpoints -- nodesMap.keySet

    ViewerGraphData(
      rootId      = data.rootId,
      arrows      = data.arrows.map(a => a.id -> a).toMap,
      groups      = data.groups.map(g => g.id -> g).toMap,
      nodes       = nodesMap ++ implicitNodeIds.map(n => n -> ViewerNode(n)),
      memberships = data.memberships.toMap // This messes up with the order of elements
    )

end ViewerGraphData
