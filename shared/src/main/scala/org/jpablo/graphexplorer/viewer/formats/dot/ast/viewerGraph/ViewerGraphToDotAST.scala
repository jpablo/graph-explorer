package org.jpablo.graphexplorer.viewer.formats.dot.ast.viewerGraph

import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.formats.dot.ast.*
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, ViewerGraphElements}
import org.jpablo.graphexplorer.viewer.models.ViewerElement.idAttributeKey
import org.jpablo.graphexplorer.viewer.models.*

def graphToDotAST(graph: ViewerGraph): DotAST =
  DotAST(
    tpe = graph.tpe.toString,
    children = viewerGraphElementsToDotGraphElements(graph.combineStyleAttributes),
    id = Some(graph.id)
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

private def attrs(target: AttributeTarget, attrs: Attributes) =
  if attrs.values.nonEmpty then
    List(AttrStmt(target.toString, attrs.values.map((id, value) => Attr(id.value, value)).toList))
  else
    Nil

// TODO: Add more tests for this function
def viewerGraphElementsToDotGraphElements(elements: ViewerGraphElements): List[GraphElement] =

  def belongsToGroup(memberId: GroupMemberId, groupId: Option[GroupId]): Boolean =
    elements.memberships.get(memberId) == groupId

  def groupToSubGraph(groupId: GroupId, visited: Set[GroupId] = Set()): Option[SubGraph] =
    if groupId in visited then
      None
    else
      val nodeStmts = elements.nodes
        .filter((nId, _) => belongsToGroup(nId, Some(groupId))).map(nodeToStmt)

      val subGraphs = elements.groups
        .filter((gId, _) => belongsToGroup(gId, Some(groupId)))
        .flatMap((gId, _) => groupToSubGraph(gId, visited + groupId))
        .toList

      val viewerGroup = elements.groups(groupId)
      val groupAttrs  = attrs(AttributeTarget.graph, viewerGroup.attributes)
      // only 3 types of children are allowed in a subgraph:
      // 1. Cluster attributes (i.e. the current group configuration)
      // 2. SubGraphs
      // 3. Nodes
      val children = groupAttrs ++ subGraphs ++ nodeStmts
      Some(SubGraph(children, Some(groupId.value)))

  def topLevelGraphToSubGraph: Option[SubGraph] =
    val nodeStmts = elements.nodes
      .filter((nId, _) => belongsToGroup(nId, None))
      .map(nodeToStmt)

    val edgeStmts = elements.arrows.values.map(arrowToStmt)

    val subGraphs = elements.groups
      .filter((gId, _) => belongsToGroup(gId, None))
      .flatMap((gId, _) => groupToSubGraph(gId))
      .toList

    val graphAttributes        = attrs(AttributeTarget.graph, elements.graphAttributes)
    val defaultNodeAttributes  = attrs(AttributeTarget.node, elements.defaultNodeAttributes)
    val defaultArrowAttributes = attrs(AttributeTarget.edge, elements.defaultArrowAttributes)
    val defaultGroupAttributes = attrs(AttributeTarget.graph, elements.defaultGroupAttributes)

    val children =
      graphAttributes ++
        defaultNodeAttributes ++
        defaultArrowAttributes ++
        defaultGroupAttributes ++
        subGraphs ++
        nodeStmts ++
        edgeStmts

    Some(SubGraph(children, None))

  topLevelGraphToSubGraph.map(_.children).getOrElse(Nil)
