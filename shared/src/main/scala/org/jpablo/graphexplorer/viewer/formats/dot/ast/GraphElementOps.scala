package org.jpablo.graphexplorer.viewer.formats.dot.ast

import org.jpablo.graphexplorer.viewer.models.*

import scala.annotation.tailrec

extension (graphElement: GraphElement)
  def findAllViewerNodes: Set[ViewerNode] =
    @tailrec
    def loop(
        remaining: List[GraphElement],
        acc:       Map[String, Map[AttributeId, AttrValue]]
    ): Map[String, Map[AttributeId, AttrValue]] =
      remaining match
        case Nil => acc

        case (e: EdgeStmt) :: t         => loop(remaining = e.toGraphElements ++ t, acc)
        case SubGraph(children, _) :: t => loop(remaining = children ++ t, acc)

        case NodeStmt(nodeId, attr_list) :: t =>
          val attrMap = toAttrsMap(attr_list)
          loop(
            remaining = t,
            acc       = acc.updatedWith(nodeId.id)(_.fold(Some(attrMap))(existing => Some(existing ++ attrMap)))
          )

        case _ :: t => loop(remaining = t, acc)

    loop(List(graphElement), Map.empty)
      .map((id, attrs) => ViewerNode(NodeId(id), Attributes(attrs)))
      .toSet

end extension
