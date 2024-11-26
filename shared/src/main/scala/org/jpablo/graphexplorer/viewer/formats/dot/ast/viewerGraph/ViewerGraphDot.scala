package org.jpablo.graphexplorer.viewer.formats.dot.ast.viewerGraph

import org.jpablo.graphexplorer.viewer.formats.dot.ast.*
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, FlattenedGraphElement}
import org.jpablo.graphexplorer.viewer.models.{Arrow, Attributes, NodeId, ViewerGroup, ViewerNode}

def graphToDotAST(graph: ViewerGraph): DotAST =
//  pprint.log(graph)
  // Combine all elements into a DotAST
  DotAST(
    tpe      = graph.tpe,
    children = directChildrenToAST(graph.data),
    id       = graph.id
  )

def directChildrenToAST(viewerGraphData: FlattenedGraphElement): List[GraphElement] =
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
    val groupData = viewerGraphData.groups.find(_.id == groupId).get

    // Get direct children using memberships
    val directNodes = viewerGraphData.nodes
      .filter(node =>
        viewerGraphData.memberships
          .find(_._1 == node.id)
          .exists(_._2.contains(groupId))
      )
      .map(nodeToStmt)

    val directArrows = viewerGraphData.arrows
      .filter(arrow =>
        viewerGraphData.memberships
          .find(_._1 == arrow.nodeId)
          .exists(_._2.contains(groupId))
      )
      .map(arrowToStmt)

    val directGroups = viewerGraphData.groups
      .filter(group =>
        viewerGraphData.memberships
          .find(_._1 == group.id)
          .exists(_._2.contains(groupId))
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
    .find(group =>
      viewerGraphData.memberships
        .find(_._1 == group.id)
        .forall(_._2.isEmpty)
    )

  rootGroup match
    case Some(root) =>
      // Convert root group's direct children
      val directNodes = viewerGraphData.nodes
        .filter(node =>
          viewerGraphData.memberships
            .find(_._1 == node.id)
            .exists(_._2.contains(root.id))
        )
        .map(nodeToStmt)

      val directArrows = viewerGraphData.arrows
        .filter(arrow =>
          viewerGraphData.memberships
            .find(_._1 == arrow.nodeId)
            .exists(_._2.contains(root.id))
        )
        .map(arrowToStmt)

      val directGroups = viewerGraphData.groups
        .filter(group =>
          viewerGraphData.memberships
            .find(_._1 == group.id)
            .exists(_._2.contains(root.id))
        )
        .map(g => groupToSubGraph(g.id))

      directNodes ++ directArrows ++ directGroups

    case None => Nil


