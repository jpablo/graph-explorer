package org.jpablo.graphexplorer.viewer.graph

import org.jpablo.graphexplorer.viewer.models.{Arrow, NodeId, ViewerGroup, ViewerNode}

case class ViewerGraphData(
    arrows:      Map[NodeId, Arrow],
    groups:      Map[NodeId, ViewerGroup],
    nodes:       Map[NodeId, ViewerNode],
    memberships: Map[NodeId, Option[NodeId]]
):
  def removeNodes(ids: Set[NodeId]): ViewerGraphData =
    val newArrows = arrows.view
      .filterKeys(id =>
        val arrow = arrows(id)
        !ids.contains(arrow.source) && !ids.contains(arrow.target)
      )
      .toMap

    copy(
      nodes       = nodes -- ids,
      arrows      = newArrows,
      groups      = groups -- ids,
      memberships = memberships -- ids
    )
