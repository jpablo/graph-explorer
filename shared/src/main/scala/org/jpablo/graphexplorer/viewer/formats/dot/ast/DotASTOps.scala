package org.jpablo.graphexplorer.viewer.formats.dot.ast

import com.softwaremill.quicklens.*
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.NodeId
import org.jpablo.graphexplorer.viewer.utils.Utils.randomUUIDSafe

import scala.annotation.tailrec

enum AttributeTarget:
  case node, edge, graph

def randomId(): String = randomUUIDSafe()

extension (ast: DotAST)

  def toViewerGraph: ViewerGraph =
    val data = findAllDirectChildren(ast.asSubgraph)
    ViewerGraph.fromViewerGraphData(data)

  def addRandomNode(): DotAST =
    val label = Attr("label", "")
    val newNode = NodeStmt(DotNodeId(randomId()), List(label))
    ast.modify(_.children).using(_ ++ List(Newline(), Pad(), newNode, Newline()))

  def addEdge(source: NodeId, target: NodeId): DotAST =
    val newEdge = EdgeStmt(List(DotNodeId(source.value), DotNodeId(target.value)), Nil)
    ast.modify(_.children).using(_ ++ List(Newline(), Pad(), newEdge, Newline()))

  def addNodeAndEdge(source: NodeId): DotAST =
    val newNodeId = randomId()
    val label = Attr("label", "")
    val newNode = NodeStmt(DotNodeId(newNodeId), List(label))
    val newEdge = EdgeStmt(List(DotNodeId(source.value), DotNodeId(newNodeId)), Nil)
    ast.modify(_.children).using(_ ++ List(Newline(), Pad(), newNode, Newline(), newEdge, Newline()))

  def updateDiagramAttributes(target: AttributeTarget)(attrs: Map[String, String]): DotAST =
    val targetStr = target.toString
    var attrMap = attrs

    def updateAttrs(attrs: List[Attr]): List[Attr] =
      for attr <- attrs
      yield
        if attrMap.contains(attr.id) then
          val newAttrValue = attrMap(attr.id)
          attrMap -= attr.id
          Attr(attr.id, newAttrValue)
        else
          attr
    // first update existing attributes
    val updatedChildren =
      ast.children.map:
        case AttrStmt(`targetStr`, attrs) => AttrStmt(targetStr, updateAttrs(attrs))
        case other                        => other
    // then add remaining attributes to a single AttrStmt
    val newAttrs = AttrStmt(targetStr, attrMap.map((k, v) => Attr(k, v)).toList)
    ast.copy(children = newAttrs :: updatedChildren)

  def getDiagramAttributes(target: AttributeTarget): Map[String, String] =
    val targetStr = target.toString
    ast.children
      .collect:
        case AttrStmt(`targetStr`, attrs) => attrs.map(attr => attr.id -> attr.value)
      .flatten
      .toMap

  /** Unsupported features:
    *   - graph size (results in an incorrect layout)
    */
  def removeUnsupportedFeatures: DotAST =
    ast.modify(_.children).using:
      _.filter:
        case AttrStmt("graph", List(Attr("size", _))) => false
        case _                                        => true

  def setDefaultTheme: DotAST =
    ast.modify(_.children).using: children =>
      Newline() :: Pad() :: AttrStmt("node", List(Attr("style", "filled"))) :: children

  def attachInternalAttributes: DotAST =
    EdgeStmt.resetId()
    ast.modify(_.children).using(_.map(_.attachId))

  def removeNodes(idsToRemove: Set[NodeId]): DotAST =
    if idsToRemove.isEmpty then ast
    else
      pprint.log(idsToRemove, "removeNodes")
      val removed = ast.asSubgraph.removeGraphNodes(idsToRemove.map(_.value), debug = true)
      DotAST(ast.tpe, removed, ast.id)
//      ast
//        .modify(_.children)
//        .using(_.flatMap(_.removeGraphNodes(idsToRemove.map(_.value))))

  def groupNodes(ids: Set[NodeId]): DotAST =
    val idsStr = ids.map(_.value)
    val clusterId = s"cluster_${randomId()}"
    // TODO: we need to get the attributes!
    val cluster = SubGraph(
      children = idsStr.toList.map(id => NodeStmt(DotNodeId(id), attr_list = Nil)),
      id       = Some(clusterId)
    )
    ast.removeNodes(ids).modify(_.children).using(_ :+ cluster)

  def optimize: DotAST =
    @tailrec
    def loop(children: List[GraphElement], state: List[GraphElement] = Nil): List[GraphElement] =
      children match
        case h :: EdgeStmt(Nil, _) :: t => loop(h :: t, state) // why the focus on the 2nd element?
        case Pad() :: Newline() :: t    => loop(t, state)
        case h :: t                     => loop(t, h :: state)
        case Nil                        => state.reverse

    ast.modify(_.children).using(loop(_))
