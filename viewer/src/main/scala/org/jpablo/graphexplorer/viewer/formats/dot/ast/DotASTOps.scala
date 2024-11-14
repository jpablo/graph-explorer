package org.jpablo.graphexplorer.viewer.formats.dot.ast

import com.softwaremill.quicklens.*
import org.jpablo.graphexplorer.viewer.extensions.*
import org.jpablo.graphexplorer.viewer.formats.dot.DotText
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.{NodeId, ViewerNode}

import scala.annotation.tailrec

enum AttributeTarget:
  case node, edge, graph

extension (ast: DotAST)

  def renderToDot: DotText =
    DotText(ast.render(true))

  def toViewerGraph: ViewerGraph =
    ViewerGraph(
      arrows = ast.allArrows,
      nodes  = ast.allNodesIds.map(ViewerNode.node)
    )

  def addEdge(source: NodeId, target: NodeId): DotAST =
    val newEdge = EdgeStmt(List(DotNodeId(source.value), DotNodeId(target.value)), Nil)
    ast.modify(_.children).using(_ ++ List(Newline(), Pad(), newEdge, Newline()))

  def withAttributes(target: AttributeTarget)(attrs: Map[String, String]): DotAST =
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
    ast.copy(
      children = updatedChildren match
        case Newline() :: _ => newAttrs :: updatedChildren
        case _              => Newline() :: Pad() :: newAttrs :: updatedChildren
    )

  def getAttributes(target: AttributeTarget): Map[String, String] =
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
      AttrStmt("node", List(Attr("style", "filled"))) :: children

  def attachInternalAttributes: DotAST =
    EdgeStmt.resetId()
    ast.modify(_.children).using(_.map(_.attachId))

  def removeNodes(idsToRemove: Set[String]): DotAST =
    @tailrec
    def optimize(children: List[GraphElement], state: List[GraphElement] = Nil): List[GraphElement] =
      children match
        case h :: EdgeStmt(Nil, _) :: t => optimize(h :: t, state) // why the focus on the 2nd element?
        case Pad() :: Newline() :: t    => optimize(t, state)
        case h :: t                     => optimize(t, h :: state)
        case Nil                        => state.reverse

    def dedup(lst: List[GraphElement]): List[GraphElement] =
      lst
        .foldLeft((List.empty[GraphElement], Set.empty[GraphElement])):
          case ((acc, visited), e: EdgeStmt) if e in visited => (acc, visited)
          case ((acc, visited), n: NodeStmt) if n in visited => (acc, visited)
          case ((acc, visited), e)                           => (e :: acc, visited + e)
        ._1
        .reverse

    ast
      .modify(_.children).using(_.flatMap(_.removeGraphNodes(idsToRemove)))
      .modify(_.children).using(optimize(_))
      .modify(_.children).using(dedup)
