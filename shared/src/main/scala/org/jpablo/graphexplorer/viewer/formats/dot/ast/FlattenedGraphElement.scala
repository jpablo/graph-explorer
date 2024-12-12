package org.jpablo.graphexplorer.viewer.formats.dot.ast

import org.jpablo.graphexplorer.viewer.graph.ViewerGraphData
import org.jpablo.graphexplorer.viewer.models.*

case class FlattenedGraphElement(
    rootId:      GroupId,
    arrows:      List[Arrow],
    groups:      List[ViewerGroup],
    nodes:       List[ViewerNode],
    memberships: List[(ElementId, GroupId)] = Nil
):

  def toViewerGraphData =
    val arrowEndpoints = arrows.flatMap(_.endpoints).toSet
    val nodesMap = nodes.map(n => n.id -> n).toMap
    val implicitNodeIds = arrowEndpoints -- nodesMap.keySet
    ViewerGraphData(
      rootId      = rootId,
      arrows      = arrows.map(a => a.id -> a).toMap,
      groups      = groups.map(g => g.id -> g).toMap,
      nodes       = nodesMap ++ implicitNodeIds.map(n => n -> ViewerNode(n)),
      memberships = memberships.toMap // This messes up with the order of elements
    )
