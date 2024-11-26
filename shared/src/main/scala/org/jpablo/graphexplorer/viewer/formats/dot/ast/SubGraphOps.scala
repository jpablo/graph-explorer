package org.jpablo.graphexplorer.viewer.formats.dot.ast

import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.models.Arrow.arrow
import org.jpablo.graphexplorer.viewer.models.{Arrow, Attributes}

import scala.annotation.tailrec

extension (self: SubGraph)
  def updateTopLevelAttributes(nodeIds: Set[String], attributes: Attributes): SubGraph =
    val attrMap = attributes.values

    @tailrec
    def loop(
        remaining: List[GraphElement],
        acc:       List[GraphElement],
        visited:   Set[String]
    ): (List[GraphElement], Set[String]) =
      remaining match
        case Nil => (acc, visited)

        case (e @ EdgeStmt(edgeList @ List(DotNodeId(source, _), DotNodeId(target, _)), attr_list)) :: tail =>
          val edgeAttrsMap = toAttrsMap(attr_list)
          val edgeId = arrow((source, target), edgeAttrsMap).id.value
          val found = edgeId in nodeIds
          val e2 =
            if found then
              EdgeStmt(edgeList, toAttrsList((edgeAttrsMap ++ attrMap).toSeq))
            else e
          loop(remaining = tail, acc = e2 :: acc, visited = if found then visited + edgeId else visited)

        case NodeStmt(id, attr_list: List[Attr]) :: tail if id.id in nodeIds =>
          loop(remaining = tail, acc = NodeStmt(id, merge(attr_list, attrMap)) :: acc, visited + id.id)

        case h :: tail => loop(remaining = tail, acc = h :: acc, visited)

    val (children, visited) = loop(self.children, Nil, Set.empty)
    val remainingIds = nodeIds -- visited
    val newNodes = remainingIds.map(id => NodeStmt(DotNodeId(id), toAttrsList(attributes.values.toSeq)))
    self.copy(children = children.reverse ++ Seq(Newline(), Pad()) ++ newNodes)

  def findAttributes: Map[AttributeTarget, Map[String, String]] =
    self.children
      .collect:
        case AttrStmt(target, attrs) =>
          val targetEnum = AttributeTarget.valueOf(target.toLowerCase)
          (targetEnum, attrs.map(attr => attr.id -> attr.value).toMap)
      .groupBy(_._1)
      .view
      .mapValues(pairs => pairs.flatMap(_._2).toMap)
      .toMap

def toAttrsMap(attrList: List[Attr]): Map[String, String] =
  attrList.map(attr => attr.id -> attr.value).toMap

def toAttrsList(attrs: Seq[(String, String)]): List[Attr] =
  attrs.map(Attr(_, _)).toList

def merge(attr_list: List[Attr], attrs: Map[String, String]) =
  (toAttrsMap(attr_list) ++ attrs).map(Attr(_, _)).toList
