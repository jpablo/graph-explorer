package org.jpablo.graphexplorer.viewer.formats.dot.ast.viewerGraph

import org.jpablo.graphexplorer.viewer.formats.dot.ast.*
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, ViewerGraphData}
import org.jpablo.graphexplorer.viewer.models.{Arrow, Attributes, NodeId, ViewerGroup, ViewerNode}

def graphToDotAST(graph: ViewerGraph): DotAST =
//  pprint.log(graph)
  // Combine all elements into a DotAST
  DotAST(
    tpe      = graph.tpe,
    children = directChildrenToAST(graph.data),
    id       = graph.id
  )

def directChildrenToAST(viewerGraphData: ViewerGraphData): List[GraphElement] =
  // Helper function to create NodeStmt from ViewerNode
  def nodeToStmt(node: ViewerNode): NodeStmt =
    NodeStmt(
      DotNodeId(node.id.value),
      node.publicAttrs.values.map { case (key, value) => Attr(key, value) }.toList
    )

  // Helper function to create EdgeStmt from Arrow
  def arrowToStmt(arrow: Arrow): EdgeStmt =
    EdgeStmt(
      List(
        DotNodeId(arrow.source.value),
        DotNodeId(arrow.target.value)
      ),
      arrow.publicAttrs.values.map { case (key, value) => Attr(key, value) }.toList
    )

  // Helper function to create SubGraph from ViewerGroup
  def groupToSubGraph(groupId: NodeId): SubGraph =
    val groupData = viewerGraphData.groups(groupId)

    // Get direct children using memberships
    val directNodes = viewerGraphData.nodes
      .values
      .filter(node =>
        viewerGraphData.memberships.get(node.id).contains(Some(groupId))
      )
      .map(nodeToStmt)

    val directArrows = viewerGraphData.arrows
      .values
      .filter(arrow =>
        viewerGraphData.memberships.get(arrow.id).contains(Some(groupId))
      )
      .map(arrowToStmt)

    val directGroups = viewerGraphData.groups
      .values
      .filter(group =>
        viewerGraphData.memberships.get(group.id).contains(Some(groupId))
      )
      .map(g => groupToSubGraph(g.id))

    def attrs(attrs: Attributes) =
      if attrs.values.nonEmpty then
        List(AttrStmt(
          AttributeTarget.node.toString,
          attrs.values.map { case (key, value) => Attr(key, value) }.toList
        ))
      else Nil

    val nodeAttrs = attrs(groupData.nodeAttrs)
    val edgeAttrs = attrs(groupData.edgeAttrs)
    val groupAttrs = attrs(groupData.attrs)

    // Combine all elements
    val children = groupAttrs ++ edgeAttrs ++ nodeAttrs ++ directNodes ++ directArrows ++ directGroups

    SubGraph(children, Some(groupId.value))
  end groupToSubGraph

  // Find the root group (the one with no parent in memberships)
  val rootGroup = viewerGraphData.groups
    .values
    .find(group =>
      viewerGraphData.memberships.get(group.id).contains(None)
    )

  rootGroup match
    case Some(root) =>
      // Convert root group's direct children
      val directNodes = viewerGraphData.nodes
        .values
        .filter(node =>
          viewerGraphData.memberships.get(node.id).contains(Some(root.id))
        )
        .map(nodeToStmt)

      val directArrows = viewerGraphData.arrows
        .values
        .filter(arrow =>
          viewerGraphData.memberships.get(arrow.id).contains(Some(root.id))
        )
        .map(arrowToStmt)

      val directGroups = viewerGraphData.groups
        .values
        .filter(group =>
          viewerGraphData.memberships.get(group.id).contains(Some(root.id))
        )
        .map(g => groupToSubGraph(g.id))

      directNodes.toList ++ directArrows ++ directGroups

    case None => Nil

