package org.jpablo.graphexplorer.viewer.formats.dot.ast

import org.jpablo.graphexplorer.viewer.graph.ViewerGraphData
import org.jpablo.graphexplorer.viewer.models.{Arrow, ElementId, ViewerGroup, ViewerNode}

case class FlattenedGraphElement(
    arrows:      List[Arrow],
    groups:      List[ViewerGroup],
    nodes:       List[ViewerNode],
    memberships: List[(ElementId, Option[ElementId])] = Nil
):

  val rootNodeId: ElementId =
    memberships
      .collectFirst:
        case (id, None) => id
      .getOrElse(throw IllegalStateException("No root node found"))

  def toViewerGraphData =
    val arrowEndpoints = arrows.flatMap(_.endpoints).toSet
    val nodesMap = nodes.map(n => n.id -> n).toMap
    val implicitNodeIds = arrowEndpoints -- nodesMap.keySet
    val membershipsMap = memberships.toMap
    // any node with the same id as the rootNode will take precedence
    // i.e. the root node will be removed from the map
    val extraMemberships = implicitNodeIds.map(n => n -> Some(rootNodeId)).toMap
    ViewerGraphData(
      arrows      = arrows.map(a => a.id -> a).toMap,
      groups      = groups.map(g => g.id -> g).toMap,
      nodes       = nodesMap ++ implicitNodeIds.map(n => n -> ViewerNode(n)),
      memberships = membershipsMap ++ extraMemberships // This messes up with the order of elements
    )
