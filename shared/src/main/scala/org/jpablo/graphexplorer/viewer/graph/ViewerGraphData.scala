package org.jpablo.graphexplorer.viewer.graph

import org.jpablo.graphexplorer.viewer.models.{Arrow, NodeId, ViewerGroup, ViewerNode}

// rename to FlattenedGraphElement
case class ViewerGraphData(
    arrows:      List[Arrow],
    groups:      List[ViewerGroup],
    nodes:       List[ViewerNode],
    memberships: List[(NodeId, Option[NodeId])] = Nil
):

  def removeNodes(ids: Set[NodeId]): ViewerGraphData =
    copy(
      nodes  = nodes.filterNot(n => ids.contains(n.id)),
      arrows = arrows.filterNot(a => ids.contains(a.source) || ids.contains(a.target)),
      groups = groups.filterNot(g => ids.contains(g.id))
    )
