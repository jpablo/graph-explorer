package org.jpablo.graphexplorer.viewer.formats.dot.ast.viewerGraph

import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.formats.dot.ast.*
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, ViewerGraphElements}
import org.jpablo.graphexplorer.viewer.models.ViewerElement.idAttributeKey
import org.jpablo.graphexplorer.viewer.models.*

// This is used in two places:
// 1. Transforming the graph to the DOT AST for rendering
// 2. Transforming the graph to the DOT AST CodeMirror
// They don't have to be the same.
def graphToDotAST(graph: ViewerGraph): DotAST =
  DotAST(
    tpe = graph.tpe.toString,
    children = viewerGraphElementsToDotGraphElements(graph.combineStyleAttributes),
    id = Some(graph.id)
  )

private def viewerNodeToNode(id: NodeId, node: ViewerNode): NodeStmt =
  val svgIdAttr = idAttributeKey -> AttrValue(SvgNodeElementId.toSvgIdAttr(id))
  NodeStmt(
    node_id = DotNodeId(id.value),
    attr_list = (node.attributes.values + svgIdAttr).map((id, value) => Attr(id.value, value)).toList
  )

private def arrowToEdge(arrow: Arrow): EdgeStmt =
  // we'll use the arrow sequence as the id to distinguish between arrows with the same source and target
  val svgIdAttr = idAttributeKey -> AttrValue(SvgEdgeElementId.toSvgIdAttr(arrow.seq))
  // Adding an `id` attribute to control the svg ids generated
  // Example:
  // <g id="edge:1" class="edge"><title>a->b</title>...</g>
  EdgeStmt(
    edge_list = List(
      DotNodeId(arrow.source.value),
      DotNodeId(arrow.target.value)
    ),
    attr_list = (arrow.attributes.values + svgIdAttr).map((id, value) => Attr(id.value, value)).toList
  )

private def attributesToStmt(target: AttributeTarget, attrs: Attributes): List[AttrStmt] =
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
      val nodeStmts = elements.nodes.filter((nId, _) => belongsToGroup(nId, Some(groupId))).map(viewerNodeToNode)
      val subGraphs = elements.groups
        .filter((gId, _) => belongsToGroup(gId, Some(groupId)))
        .flatMap((gId, _) => groupToSubGraph(gId, visited + groupId))
        .toList
      val svgIdAttr = idAttributeKey -> AttrValue(SvgGroupElementId.toSvgIdAttr(groupId))
      val groupAttrs = attributesToStmt(AttributeTarget.graph, elements.groups(groupId).attributes + svgIdAttr)
      // only 3 types of children are allowed in a subgraph:
      // 1. Cluster attributes (i.e. the current group configuration)
      // 2. SubGraphs
      // 3. Nodes
      Some(SubGraph(
        children = groupAttrs ++ subGraphs ++ nodeStmts,
        id = Some(groupId.toDot)
      ))

  // ------------------------
  // Top level elements
  // ------------------------
  val nodeStmts = elements.nodes.filter((nId, _) => belongsToGroup(nId, None)).map(viewerNodeToNode)
  val edgeStmts = elements.arrows.values.map(arrowToEdge)
  val subGraphs = elements.groups
    .filter((gId, _) => belongsToGroup(gId, None))
    .flatMap((gId, _) => groupToSubGraph(gId))
    .toList
  // ViewerGraphElements maintains two graph attributes but when exporting back to DOT we only need one
  val graphAttributes        = attributesToStmt(AttributeTarget.graph, elements.graphAttributes ++ elements.defaultGroupAttributes)
  val defaultNodeAttributes  = attributesToStmt(AttributeTarget.node, elements.defaultNodeAttributes)
  val defaultArrowAttributes = attributesToStmt(AttributeTarget.edge, elements.defaultArrowAttributes)

  graphAttributes ++
    defaultNodeAttributes ++
    defaultArrowAttributes ++
    subGraphs ++
    nodeStmts ++
    edgeStmts
