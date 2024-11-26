package org.jpablo.graphexplorer.viewer.formats.dot.ast.viewerGraph

import org.jpablo.graphexplorer.viewer.formats.dot.ast.*
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.{Arrow, Attributes, NodeId, ViewerGroup, ViewerNode}

def graphToDotAST(graph: ViewerGraph): DotAST =
  val nodeStmts = graph.nodeById.values.map { node =>
    NodeStmt(
      DotNodeId(node.id.value),
      node.publicAttrs.values.map { case (key, value) =>
        Attr(key, value)
      }.toList
    )
  }

  val edgeStmts = graph.arrows.map { arrow =>
    EdgeStmt(
      List(
        DotNodeId(arrow.source.value),
        DotNodeId(arrow.target.value)
      ),
      arrow.publicAttrs.values.map { case (key, value) =>
        Attr(key, value)
      }.toList
    )
  }

  // Combine all elements into a DotAST
  DotAST(
    tpe      = "digraph",
    children = (nodeStmts ++ edgeStmts).toList,
    id       = None
  )

case class ViewerGraphData(
    arrows: List[(Option[NodeId], Arrow)],
    groups: List[(Option[NodeId], ViewerGroup)],
    nodes:  List[(Option[NodeId], ViewerNode)]
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
    val groupData = viewerGraphData.groups.find(_._2.id == groupId).get._2

    // Get direct children of this group
    val directNodes = viewerGraphData.nodes
      .filter(_._1.contains(groupId))
      .map(_._2)
      .map(nodeToStmt)

    val directArrows = viewerGraphData.arrows
      .filter(_._1.contains(groupId))
      .map(_._2)
      .map(arrowToStmt)

    val directGroups = viewerGraphData.groups
      .filter(_._1.contains(groupId))
      .map(_._2)
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

  // Find the root group (the one with None as parent)
  val rootGroup = viewerGraphData.groups.find(_._1.isEmpty).map(_._2)

  rootGroup match
    case Some(root) =>
      // Convert root group's direct children
      val directNodes = viewerGraphData.nodes
        .filter(_._1.contains(root.id))
        .map(_._2)
        .map(nodeToStmt)

      val directArrows = viewerGraphData.arrows
        .filter(_._1.contains(root.id))
        .map(_._2)
        .map(arrowToStmt)

      val directGroups = viewerGraphData.groups
        .filter(_._1.contains(root.id))
        .map(_._2)
        .map(g => groupToSubGraph(g.id))

      directNodes.toList ++ directArrows ++ directGroups

    case None => Nil
