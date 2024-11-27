package org.jpablo.graphexplorer.viewer.graph

import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.models.{Arrow, NodeId, ViewerGroup, ViewerNode}

import scala.collection.mutable

case class ViewerGraphData(
    arrows:      mutable.LinkedHashMap[NodeId, Arrow],
    groups:      Map[NodeId, ViewerGroup],
    nodes:       Map[NodeId, ViewerNode],
    memberships: mutable.LinkedHashMap[NodeId, Option[NodeId]]
):
  assert(memberships.nonEmpty, "At least one membership is required (the root node)")
  // fail fast if no root node is provided
  def rootNodeId: NodeId =
    memberships
      .collectFirst:
        case (id, None) => id
      .getOrElse(throw IllegalStateException("No root node found"))

  assert(rootNodeId in groups, s"Root node $rootNodeId not found in groups: $groups")

  def root: ViewerGroup =
    groups(rootNodeId)

  def nodesSet = nodes.values.toSet
  def arrowsSet = arrows.values.toSet

  def removeNodes(ids: Set[NodeId]): ViewerGraphData =
    val a2 = arrows --= arrows.keys.filter(id => ids.contains(arrows(id).source) || ids.contains(arrows(id).target))
//    val newArrows = arrows
//      .filterKeys: id =>
//        val arrow = arrows(id)
//        !ids.contains(arrow.source) && !ids.contains(arrow.target)
    copy(
      nodes       = nodes -- ids,
      arrows      = a2,
      groups      = groups -- ids,
      memberships = memberships.subtractAll(ids)
    )

  def arrowSequences(source: NodeId, target: NodeId): List[Int] =
    arrows.values
      .filter(a => a.source == source && a.target == target)
      .map(_.seq)
      .toList

  def maxArrowSequence(source: NodeId, target: NodeId): Int = {
    val seqs = arrowSequences(source, target)
    if seqs.isEmpty then 0 else seqs.max
  }
