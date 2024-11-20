package org.jpablo.graphexplorer.viewer.formats.dot.ast

import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.models.{Arrow, Attributes}

import scala.annotation.tailrec

extension (self: Subgraph)
  def updateTopLevelAttributes(nodeIds: Set[String], attributes: Attributes): Subgraph =
    val attrMap = attributes.values

    @tailrec
    def loop(
        remaining: List[GraphElement],
        acc:       List[GraphElement],
        visited:   Set[String]
    ): (List[GraphElement], Set[String]) =
      remaining match
        case Nil => (acc, visited)

        case (e @ EdgeStmt(edgeList @ List(DotNodeId(e1, _), DotNodeId(e2, _)), attr_list)) :: tail =>
          val edgeAttrsMap = toAttrsMap(attr_list)
          val arrowId = Arrow((e1, e2), edgeAttrsMap).nodeId.value
          pprint.log((arrowId, attr_list))
          val found = arrowId in nodeIds
          val e3 =
            if found then
              EdgeStmt(edgeList, toAttrsList((edgeAttrsMap ++ attrMap).toSeq))
            else e
          loop(remaining = tail, acc = e3 :: acc, visited)

        case NodeStmt(id, attr_list: List[Attr]) :: tail if id.id in nodeIds =>
          loop(remaining = tail, acc = NodeStmt(id, merge(attr_list, attrMap)) :: acc, visited + id.id)

        case h :: tail => loop(remaining = tail, acc = h :: acc, visited)

    val (children, visited) = loop(self.children, Nil, Set.empty)
    val remainingIds = nodeIds -- visited
    val newNodes = remainingIds.map(id => NodeStmt(DotNodeId(id), toAttrsList(attributes.values.toSeq)))
    self.copy(children = children.reverse ++ Seq(Newline(), Pad()) ++ newNodes)

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

def toAttrsList(attrs: Seq[(String, String)]): List[Attr] =
  attrs.map(Attr(_, _)).toList

def merge(attr_list: List[Attr], attrs: Map[String, String]) =
  (toAttrsMap(attr_list) ++ attrs).map(Attr(_, _)).toList
