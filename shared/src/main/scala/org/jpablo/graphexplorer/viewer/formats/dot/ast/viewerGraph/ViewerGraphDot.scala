package org.jpablo.graphexplorer.viewer.formats.dot.ast.viewerGraph

import org.jpablo.graphexplorer.viewer.formats.dot.ast.*
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, ViewerGraphData}
import org.jpablo.graphexplorer.viewer.models.Attributable.idAttributeKey
import org.jpablo.graphexplorer.viewer.models.{Arrow, Attributes, GroupId, ViewerNode}

def graphToDotAST(graph: ViewerGraph): DotAST =
  DotAST(
    tpe      = graph.tpe,
    children = graphDataToDotGraphElements(graph.data),
    id       = Some(graph.id)
  )

private def nodeToStmt(node: ViewerNode): NodeStmt =
  NodeStmt(DotNodeId(node.id.value), node.attrs.values.map(Attr(_, _)).toList)

private def arrowToStmt(arrow: Arrow): EdgeStmt = {
  // we'll use the arrow sequence as the id to distinguish between arrows with the same source and target
  val seqAsId = idAttributeKey -> AttrValue(arrow.seq.toString)
  EdgeStmt(
    edge_list = List(
      DotNodeId(arrow.source.value),
      DotNodeId(arrow.target.value)
    ),
    attr_list = (arrow.attrs.values + seqAsId).map(Attr(_, _)).toList
  )
}

private def attrs(attrs: Attributes, target: AttributeTarget) =
  if attrs.values.nonEmpty then
    List(AttrStmt(target.toString, attrs.values.map(Attr(_, _)).toList))
  else Nil

def graphDataToDotGraphElements(graphData: ViewerGraphData): List[GraphElement] =
  def groupToSubGraph(groupId: GroupId, visited: Set[GroupId] = Set()): Option[SubGraph] =
    if visited contains groupId then
      None
    else
      val nodeStmts = graphData.nodes.values
        .filter(node => graphData.getMembership(node.id) == groupId)
        .map(nodeToStmt)

      val edgeStmts = graphData.arrows.values
        .filter(arrow => graphData.getMembership(arrow.id) == groupId)
        .map(arrowToStmt)

      val subGraphs = graphData.groups.values
        .filter(group => graphData.getMembership(group.id) == groupId)
        .flatMap(g => groupToSubGraph(g.id, visited + groupId))
        .toList
        .sortBy(_.id) // TODO: Is this really necessary?


      val viewerGroup = graphData.groups(groupId)
      val nodeAttrs = attrs(viewerGroup.nodeAttrs, AttributeTarget.node)
      val edgeAttrs = attrs(viewerGroup.edgeAttrs, AttributeTarget.edge)
      val groupAttrs = attrs(viewerGroup.attrs, AttributeTarget.graph)

      // Combine all elements
      val children = groupAttrs ++ nodeAttrs ++ edgeAttrs ++ subGraphs ++ nodeStmts ++ edgeStmts

      Some(SubGraph(children, Some(groupId.value)))
  end groupToSubGraph

  groupToSubGraph(graphData.root.id).toList.flatMap(_.children)
