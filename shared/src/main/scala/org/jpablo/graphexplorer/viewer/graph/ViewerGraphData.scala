package org.jpablo.graphexplorer.viewer.graph

import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.models.{Arrow, Attributes, NodeId, ViewerGroup, ViewerNode}

type Arrows = Map[NodeId, Arrow]
type Memberships = Map[NodeId, Option[NodeId]]

case class ViewerGraphData(
    arrows:      Arrows,
    groups:      Map[NodeId, ViewerGroup],
    nodes:       Map[NodeId, ViewerNode],
    memberships: Memberships
):
  assert(memberships.nonEmpty, "At least one membership is required (the root node)")
  // fail fast if no root node is provided
  def rootNodeId: NodeId =
    memberships
      .collectFirst:
        case (id, None) => id
      .getOrElse(throw IllegalStateException("No root node found"))

  assert(rootNodeId in groups, s"Root node $rootNodeId not found in groups: $groups")

  var arrowValues: Iterable[Arrow] = arrows.values
  var arrowsSet: Set[Arrow] = arrowValues.toSet
  var arrowsMap: Map[NodeId, Arrow] = arrows

  def filterArrows(f: ((NodeId, Arrow)) => Boolean) =
    arrows.filter(f)

  def addArrow(source: NodeId, target: NodeId): ViewerGraphData =
    val newSeq = maxArrowSequence(source, target)
    val arrow = Arrow(source, target, seq = newSeq + 1)
    copy(
      arrows      = arrows + (arrow.id -> arrow),
      memberships = memberships + (arrow.id -> Some(rootNodeId))
    )

  def concatArrows(other: Arrows) =
    arrows ++ other

  def addMembership(nodeId: NodeId, groupId: Option[NodeId]): ViewerGraphData =
    copy(memberships = memberships + (nodeId -> groupId))

  def addNode(nodeId: NodeId, label: String = "", groupId: Option[NodeId] = None): ViewerGraphData =
    copy(
      nodes       = nodes + (nodeId -> ViewerNode(nodeId, Attributes(Map("label" -> AttrValue(label))))),
      memberships = memberships + (nodeId -> groupId.orElse(Some(rootNodeId)))
    )

  val root: ViewerGroup =
    groups(rootNodeId)

  val nodesSet = nodes.values.toSet

  def removeNodes(ids: Set[NodeId]): ViewerGraphData =
    val remainingArrows =
      arrows -- arrows.keys.filter(id => ids.contains(arrows(id).source) || ids.contains(arrows(id).target))
    copy(
      nodes       = nodes -- ids,
      arrows      = remainingArrows,
      groups      = groups -- ids,
      memberships = memberships.removedAll(ids)
    )

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

    val arrowsToUpdate: Arrows = filterArrows((id, _) => id in arrowIdsToUpdate)
    val updatedArrows = arrowsToUpdate.transform((_, a) => a.mergeAttrs(attrs))

    val endpointsToUpdate = arrowsToUpdate.values.flatMap(_.endpoints).toSet & idsToUpdate
    // only update these if they are in ids
    val allNodeIdsToUpdate = nodeIdsToUpdate ++ endpointsToUpdate

    val updatedNodes =
      allNodeIdsToUpdate.foldLeft(nodes): (nodesMap, nodeId) =>
        nodesMap
          .updatedWith(nodeId) {
            _.fold(
              Some(ViewerNode(nodeId, attrs))
            )(n => Some(n.mergeAttrs(attrs)))
          }

    val updatedMembership =
      updatedNodes.keys.map(id => id -> memberships.getOrElse(id, Some(root.id))).toMap

    copy(
      arrows      = concatArrows(updatedArrows),
      nodes       = updatedNodes,
      memberships = memberships ++ updatedMembership
    )

end ViewerGraphData
