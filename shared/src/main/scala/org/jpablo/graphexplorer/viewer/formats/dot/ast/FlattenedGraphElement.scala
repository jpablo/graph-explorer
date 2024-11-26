package org.jpablo.graphexplorer.viewer.formats.dot.ast

import org.jpablo.graphexplorer.viewer.graph.ViewerGraphData
import org.jpablo.graphexplorer.viewer.models.{Arrow, NodeId, ViewerGroup, ViewerNode}

// rename to FlattenedGraphElement
case class FlattenedGraphElement(
    arrows:      List[Arrow],
    groups:      List[ViewerGroup],
    nodes:       List[ViewerNode],
    memberships: List[(NodeId, Option[NodeId])] = Nil
):
  def toViewerGraphData =
    ViewerGraphData(
      arrows      = arrows.map(a => a.id -> a).toMap,
      groups      = groups.map(g => g.id -> g).toMap,
      nodes       = nodes.map(n => n.id -> n).toMap,
      memberships = memberships.toMap
    )

  def removeNodes(ids: Set[NodeId]): FlattenedGraphElement =
    copy(
      nodes  = nodes.filterNot(n => ids.contains(n.id)),
      arrows = arrows.filterNot(a => ids.contains(a.source) || ids.contains(a.target)),
      groups = groups.filterNot(g => ids.contains(g.id))
    )
