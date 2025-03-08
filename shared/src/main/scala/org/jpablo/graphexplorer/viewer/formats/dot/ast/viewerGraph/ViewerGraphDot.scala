package org.jpablo.graphexplorer.viewer.formats.dot.ast.viewerGraph

import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.formats.dot.ast.*
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, ViewerGraphData}
import org.jpablo.graphexplorer.viewer.models.Attributable.idAttributeKey
import org.jpablo.graphexplorer.viewer.models.*

def graphToDotAST(graph: ViewerGraph): DotAST =
  DotAST(
    tpe      = graph.tpe,
    children = graphDataToDotGraphElements(graph.data.combineStyleAttributes),
    id       = Some(graph.id)
  )

private def nodeToStmt(id: NodeId, node: ViewerNode): NodeStmt =
  NodeStmt(DotNodeId(id.value), node.attributes.values.map((id, value) => Attr(id.value, value)).toList)

private def arrowToStmt(arrow: Arrow): EdgeStmt =
  // we'll use the arrow sequence as the id to distinguish between arrows with the same source and target
  val seqAsId = idAttributeKey -> AttrValue(arrow.seq.toString)
  EdgeStmt(
    edge_list = List(
      DotNodeId(arrow.source.value),
      DotNodeId(arrow.target.value)
    ),
    attr_list = (arrow.attributes.values + seqAsId).map((id, value) => Attr(id.value, value)).toList
  )

private def attrs(attrs: Attributes, target: AttributeTarget) =
  if attrs.values.nonEmpty then
    List(AttrStmt(target.toString, attrs.values.map((id, value) => Attr(id.value, value)).toList))
  else
    Nil

def graphDataToDotGraphElements(graphData: ViewerGraphData): List[GraphElement] =

  def groupToSubGraph(groupId: GroupId, visited: Set[GroupId] = Set()): Option[SubGraph] =
    if groupId in visited then
      None
    else
      // Filter elements that belong to this group
      val nodeStmts = graphData.nodes
        .filter((id, _) => graphData.belongsToGroup(id, groupId))
        .map(nodeToStmt)

      val edgeStmts = graphData.arrows.values
        .filter(arrow => graphData.belongsToGroup(arrow.id, groupId))
        .map(arrowToStmt)

      val subGraphs = graphData.groups
        .filter((id, _) => graphData.belongsToGroup(id, groupId))
        .flatMap((gId, _) => groupToSubGraph(gId, visited + groupId))
        .toList

      // Get group attributes
      val viewerGroup = graphData.groups(groupId)
      val nodeAttrs = attrs(viewerGroup.nodeAttrs, AttributeTarget.node)
      val edgeAttrs = attrs(viewerGroup.edgeAttrs, AttributeTarget.edge)
      val groupAttrs = attrs(viewerGroup.attributes, AttributeTarget.graph)

      // Combine all elements
      val children = groupAttrs ++ nodeAttrs ++ edgeAttrs ++  subGraphs ++ nodeStmts ++ edgeStmts
      Some(SubGraph(children, Some(groupId.value)))

  groupToSubGraph(graphData.rootId).map(_.children).getOrElse(Nil)
