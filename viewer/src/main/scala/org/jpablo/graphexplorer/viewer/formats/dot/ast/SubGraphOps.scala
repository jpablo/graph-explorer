package org.jpablo.graphexplorer.viewer.formats.dot.ast

import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.models.Attributes

import scala.annotation.tailrec

extension (self: Subgraph)
  def findAllNodeAttributes(nodeIds: Set[String]): Attributes =
    @tailrec
    def loop(remaining: List[GraphElement], acc: Map[String, String]): Map[String, String] =
      remaining match
        case Nil => acc
        case EdgeStmt(edgeList, _) :: tail =>
          val children = edgeList.collect { case Subgraph(c, _) => c }.flatten
          loop(remaining = children ++ tail, acc = acc)

        case NodeStmt(DotNodeId(id, _), attr_list) :: tail if id in nodeIds =>
          loop(remaining = tail, acc = acc ++ toAttrsMap(attr_list))
        case Subgraph(children, _) :: tail => loop(remaining = children ++ tail, acc = acc)
        case _ :: tail                     => loop(remaining = tail, acc = acc)

    Attributes(loop(self.children, Map.empty))

  def updateTopLevelNodeAttributes(nodeIds: Set[String], attributes: Attributes): Subgraph =
    val attrMap = attributes.values

    @tailrec
    def loop(remaining: List[GraphElement], acc: List[GraphElement]): List[GraphElement] =
      remaining match
        case Nil => acc

//        case EdgeStmt(edgeList, _) :: tail =>
//          val children = edgeList.collect { case Subgraph(c, _) => c }.flatten
//          loop(remaining = children ++ tail, acc = acc)

        case NodeStmt(id, attr_list: List[Attr]) :: tail if id.id in nodeIds =>
          loop(remaining = tail, acc = NodeStmt(id, merge(attr_list, attrMap)) :: acc)

//        case Subgraph(children, id) :: tail =>
//          loop(remaining = children ++ tail, acc = acc)

        case h :: tail => loop(remaining = tail, acc = h :: acc)

    self.copy(children = loop(self.children, Nil).reverse)

//  def updateDiagramAttributes(nodeIds: Set[String])(attrs: Map[String, String]): DotAST =
//    var attrMap = attrs
//    def updateAttrs(attrs: List[Attr]): List[Attr] =
//      for attr <- attrs
//        yield
//          if attrMap.contains(attr.id) then
//            val newAttrValue = attrMap(attr.id)
//            attrMap -= attr.id
//            Attr(attr.id, newAttrValue)
//          else
//            attr
//    // first update existing attributes
//    val updatedChildren =
//      self.children.map:
//        case AttrStmt(`targetStr`, attrs) => AttrStmt(targetStr, updateAttrs(attrs))
//        case other                        => other
//    // then add remaining attributes to a single AttrStmt
//    val newAttrs = AttrStmt(targetStr, attrMap.map((k, v) => Attr(k, v)).toList)
//    self.copy(
//      children = updatedChildren match
//        case Newline() :: _ => newAttrs :: updatedChildren
//        case _              => Newline() :: Pad() :: newAttrs :: updatedChildren
//    )

def toAttrsMap(attrList: List[Attr]): Map[String, String] =
  attrList.map(attr => attr.id -> attr.value).toMap

def merge(attr_list: List[Attr], attrs: Map[String, String]) =
  (toAttrsMap(attr_list) ++ attrs).map(Attr(_, _)).toList
