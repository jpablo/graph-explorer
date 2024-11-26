package org.jpablo.graphexplorer.viewer.graph

import org.jpablo.graphexplorer.viewer.models.{Arrow, NodeId, ViewerGroup, ViewerNode}

case class ViewerGraphData(
    arrows: List[(Option[NodeId], Arrow)],
    groups: List[(Option[NodeId], ViewerGroup)],
    nodes:  List[(Option[NodeId], ViewerNode)]
):

  def removeNodes(ids: Set[NodeId]): ViewerGraphData =
    copy(
      nodes  = nodes.filterNot { case (_, n) => ids.contains(n.id) },
      arrows = arrows.filterNot { case (_, a) => ids.contains(a.source) || ids.contains(a.target) },
      groups = groups.filterNot { case (_, g) => ids.contains(g.id) }
    )

case class ViewerGraphData2(
    arrows: Map[NodeId, Arrow],
    groups: Map[NodeId, ViewerGroup],
    nodes:  Map[NodeId, ViewerNode]
):
  def removeNodes(ids: Set[NodeId]): ViewerGraphData2 =
    copy(
      nodes  = nodes -- ids,
      arrows = arrows.filterNot { case (_, a) => ids.contains(a.source) || ids.contains(a.target) },
      groups = groups.filterNot { case (_, g) => ids.contains(g.id) }
    )
