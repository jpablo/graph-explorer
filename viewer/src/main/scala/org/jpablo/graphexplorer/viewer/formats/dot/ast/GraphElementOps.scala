package org.jpablo.graphexplorer.viewer.formats.dot.ast

import org.jpablo.graphexplorer.viewer.extensions.*
import org.jpablo.graphexplorer.viewer.models.{Arrow, Attributes, NodeId, ViewerNode}

import scala.annotation.tailrec

extension (graphElement: GraphElement)

  // add an attribute [id=$nextId] to all edges
  def attachId: GraphElement =
    graphElement match
      case EdgeStmt(edgeList, attrList) =>
        val edgeListWithIds = edgeList.map:
          case Subgraph(children, id) => Subgraph(children.map(_.attachId), id)
          case other                  => other

        EdgeStmt(edgeListWithIds, Attr("id", EdgeStmt.nextId.toString) :: attrList)

      case Subgraph(children, id) => Subgraph(children.map(_.attachId), id)
      case other                  => other

  def findAllViewerNodes: Set[ViewerNode] =
    @tailrec
    def loop(remaining: List[GraphElement], acc: Map[String, Map[String, String]]): Map[String, Map[String, String]] =
      remaining match
        case Nil => acc

        case EdgeStmt(edgeList, _) :: tail =>
          val edgeChildren = edgeList.flatMap:
            case n: DotNodeId => List(NodeStmt(n, Nil))
            case s: Subgraph  => s.children
          loop(remaining = edgeChildren ++ tail, acc = acc)

        case NodeStmt(nodeId, attr_list) :: tail =>
          val attrMap = toAttrsMap(attr_list)
          loop(
            remaining = tail,
            acc       = acc.updatedWith(nodeId.id)(_.fold(Some(attrMap))(existing => Some(existing ++ attrMap)))
          )

        case Subgraph(children, _) :: tail => loop(remaining = children ++ tail, acc = acc)

        case _ :: tail => loop(remaining = tail, acc = acc)

    loop(List(graphElement), Map.empty)
      .map((id, attrs) => ViewerNode(NodeId(id), Attributes(attrs)))
      .toSet

  def findAllArrows: Set[Arrow] =
    @tailrec
    def loop(remaining: List[GraphElement], acc: Set[Arrow] = Set.empty): Set[Arrow] =
      remaining match
        case Nil => acc
        case h :: remaining1 =>
          h match
            case e: EdgeStmt =>
              // TODO: Review if we actually need to process remaining2
              val (edgeChildren, edgeArrows) = e.allArrows1.unzip
              loop(remaining = edgeChildren.flatten ++ remaining1, acc = acc ++ edgeArrows.toSet.flatten)

            case Subgraph(children, _) => loop(remaining = children ++ remaining1, acc = acc)
            case _                     => loop(remaining = remaining1, acc = acc)

    loop(List(graphElement))

  // TODO: make this tail recursive
  def removeGraphNodes(idsToRemove: Set[String]): List[GraphElement] =
    graphElement match
      case n: NodeStmt if n.node_id.id in idsToRemove => Nil

      case EdgeStmt(edgeList, attrList) =>
        val remainingEdges: List[EdgeElement] = edgeList
          .flatMap:
            // embed DotNodeId in a NodeStmt to have a common type (GraphElement)
            case n: DotNodeId => NodeStmt(n, Nil).removeGraphNodes(idsToRemove)
            case s: Subgraph  => s.removeGraphNodes(idsToRemove)
          .map:
            // extract the NodeStmt to conform to EdgeElement = NodeStmt | Subgraph
            case n: NodeStmt => n.node_id
            case g: Subgraph => g
            // if it happens it's a bug!
            case other => throw Exception(s"Unexpected element in edge list: $other")

        // Is this faster than `remainingEdges.length < 2` ?
        remainingEdges match
          case _ :: _ :: _ => List(EdgeStmt(remainingEdges, attrList))
          case _           => Nil

      case Subgraph(children, id) =>
        val remainingChildren = children.flatMap(_.removeGraphNodes(idsToRemove))
        if remainingChildren.isEmpty then Nil else List(Subgraph(remainingChildren, id))

      case other => List(other)
