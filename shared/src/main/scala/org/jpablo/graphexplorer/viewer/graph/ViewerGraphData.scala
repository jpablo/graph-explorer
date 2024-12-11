package org.jpablo.graphexplorer.viewer.graph

import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.formats.dot.ast.{AttrValue, SubGraph}
import org.jpablo.graphexplorer.viewer.models.{Arrow, Attributes, ElementId, ViewerGroup, ViewerNode}

type Arrows = Map[ElementId, Arrow]
type Memberships = Map[ElementId, Option[ElementId]]

case class ViewerGraphData(
    arrows:      Arrows,
    groups:      Map[ElementId, ViewerGroup],
    nodes:       Map[ElementId, ViewerNode],
    memberships: Memberships
):
  assert(memberships.nonEmpty, "At least one membership is required (the root node)")
  // fail fast if no root node is provided
  val rootNodeId: ElementId =
    memberships
      .collectFirst:
        case (id, None) => id
      .getOrElse(throw IllegalStateException("No root node found"))

  assert(rootNodeId in groups, s"Root node $rootNodeId not found in groups: $groups")

  val arrowValues: Iterable[Arrow] = arrows.values
  val arrowsSet: Set[Arrow] = arrowValues.toSet

  def filterArrows(f: ((ElementId, Arrow)) => Boolean) =
    arrows.filter(f)

  def addArrow(source: ElementId, target: ElementId): ViewerGraphData =
    val newSeq = maxArrowSequence(source, target)
    val arrow = Arrow(source, target, seq = newSeq + 1)
    copy(
      arrows      = arrows + (arrow.id -> arrow),
      memberships = memberships + (arrow.id -> Some(rootNodeId))
    )

  def concatArrows(other: Arrows) =
    arrows ++ other

  def addToGroup(nodeId: ElementId, groupId: ElementId): ViewerGraphData =
    copy(memberships = memberships + (nodeId -> Some(groupId)))

  def removeEmptyGroups: ViewerGraphData =
    val nonEmptyGroups = memberships.values.collect { case Some(id) => id }.toSet
    val emptyGroups = groups.keySet -- nonEmptyGroups
    copy(groups = groups -- emptyGroups)

  def addToNewGroup(ids: Set[ElementId], label: String = ""): ViewerGraphData =
    val groupId = ElementId(s"cluster_${SubGraph.randomId()}")
    val group = ViewerGroup(groupId, Attributes(Map("label" -> AttrValue(label))))
    val groupMem = groupId -> Some(rootNodeId)
    val idsMem = ids.map(_ -> Some(groupId))
    copy(
      groups = groups + (groupId -> group),
      // This will overwrite any existing memberships, effectively moving the nodes to the new group
      memberships = (memberships ++ idsMem) + groupMem
    ).removeEmptyGroups

  def addNode(nodeId: ElementId, label: String = "", groupId: Option[ElementId] = None): ViewerGraphData =
    copy(
      nodes       = nodes + (nodeId -> ViewerNode(nodeId, Attributes(Map("label" -> AttrValue(label))))),
      memberships = memberships + (nodeId -> groupId.orElse(Some(rootNodeId)))
    )

  val root: ViewerGroup =
    groups(rootNodeId)

  val nodesSet = nodes.values.toSet

  def removeNodes(ids: Set[ElementId]): ViewerGraphData =
    val remainingArrows =
      arrows -- arrows.keys.filter(id => ids.contains(arrows(id).source) || ids.contains(arrows(id).target))
    copy(
      nodes       = nodes -- ids,
      arrows      = remainingArrows,
      groups      = groups -- ids,
      memberships = memberships.removedAll(ids)
    )

  def arrowSequences(source: ElementId, target: ElementId): List[Int] =
    arrowValues
      .filter(a => a.source == source && a.target == target)
      .map(_.seq)
      .toList

  def maxArrowSequence(source: ElementId, target: ElementId): Int =
    val seqs = arrowSequences(source, target)
    if seqs.isEmpty then 0 else seqs.max

  def updateAttributes(idsToUpdate: Set[ElementId], attrs: Attributes): ViewerGraphData =
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
