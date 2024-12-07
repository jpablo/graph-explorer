package org.jpablo.graphexplorer.viewer.formats.dot.ast.viewerGraph

import org.jpablo.graphexplorer.viewer.formats.dot.ast.*
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, ViewerGraphData}
import org.jpablo.graphexplorer.viewer.models.{Arrow, Attributes, NodeId, ViewerNode}

def graphToDotAST(graph: ViewerGraph): DotAST =
  DotAST(
    tpe      = graph.tpe,
    children = graphDataToAST(graph.data),
    id       = graph.id
  )

private def nodeToStmt(node: ViewerNode): NodeStmt =
  NodeStmt(DotNodeId(node.id.value), node.publicAttrs.values.map(Attr(_, _)).toList)

private def arrowToStmt(arrow: Arrow): EdgeStmt =
  EdgeStmt(
    List(
      DotNodeId(arrow.source.value),
      DotNodeId(arrow.target.value)
    ),
    arrow.publicAttrs.values.map(Attr(_, _)).toList
  )

private def buildNodeStmt(viewerGraphData: ViewerGraphData, groupId: NodeId): Iterable[NodeStmt] =
  viewerGraphData.nodes.values
    .filter(node => viewerGraphData.memberships.get(node.id).contains(Some(groupId)))
    .map(nodeToStmt)

private def buildEdgeStmt(viewerGraphData: ViewerGraphData, groupId: NodeId): Iterable[EdgeStmt] =
  viewerGraphData.arrowValues
    .filter(node => viewerGraphData.memberships.get(node.id).contains(Some(groupId)))
    .map(arrowToStmt)

private def attrs(attrs: Attributes, target: AttributeTarget) =
  if attrs.values.nonEmpty then
    List(AttrStmt(target.toString, attrs.values.map(Attr(_, _)).toList))
  else Nil

def graphDataToAST(viewerGraphData: ViewerGraphData): List[GraphElement] =

  def groupToSubGraph(groupId: NodeId): SubGraph =
    val groupData = viewerGraphData.groups(groupId)

    val nodeStmts = buildNodeStmt(viewerGraphData, groupId)
    val edgeStmts = buildEdgeStmt(viewerGraphData, groupId)
    val subGraphs = viewerGraphData.groups.values
      .filter(group => viewerGraphData.memberships.get(group.id).contains(Some(groupId)))
      .map(g => groupToSubGraph(g.id))

    val nodeAttrs = attrs(groupData.nodeAttrs, AttributeTarget.node)
    val edgeAttrs = attrs(groupData.edgeAttrs, AttributeTarget.edge)
    val groupAttrs = attrs(groupData.attrs, AttributeTarget.graph)

    // Combine all elements
    val children = groupAttrs ++ nodeAttrs ++ edgeAttrs ++ subGraphs ++ nodeStmts ++ edgeStmts

    SubGraph(children, Some(groupId.value))
  end groupToSubGraph

  // Find the root group (the one with no parent in memberships)
  val root = viewerGraphData.root

  groupToSubGraph(root.id).children
