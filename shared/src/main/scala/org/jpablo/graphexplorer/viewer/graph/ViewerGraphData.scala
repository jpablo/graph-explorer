package org.jpablo.graphexplorer.viewer.graph

import org.jpablo.graphexplorer.viewer.models.{Arrow, NodeId, ViewerGroup, ViewerNode}

case class ViewerGraphData(
    arrows:      Map[NodeId, Arrow],
    groups:      Map[NodeId, ViewerGroup],
    nodes:       Map[NodeId, ViewerNode],
    memberships: Map[NodeId, Option[NodeId]]
):
  // fail fast if no root node is provided
  val rootNodeId: NodeId =
    memberships
      .collectFirst:
        case (id, None) => id
      .getOrElse(throw IllegalStateException("No root node found"))

  val root: ViewerGroup =
    groups(rootNodeId)

  def removeNodes(ids: Set[NodeId]): ViewerGraphData =
    val newArrows = arrows.view
      .filterKeys: id =>
        val arrow = arrows(id)
        !ids.contains(arrow.source) && !ids.contains(arrow.target)
      .toMap
    copy(
      nodes       = nodes -- ids,
      arrows      = newArrows,
      groups      = groups -- ids,
      memberships = memberships -- ids
    )

  def arrowSequences(source: NodeId, target: NodeId): List[Int] =
    arrows.values
      .filter(a => a.source == source && a.target == target)
      .map(_.seq)
      .toList
