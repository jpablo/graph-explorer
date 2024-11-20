package org.jpablo.graphexplorer.viewer.formats.dot.ast

import org.jpablo.graphexplorer.viewer.extensions.*
import org.jpablo.graphexplorer.viewer.models.{Arrow, ViewerNode}

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
    def loop(remaining: List[GraphElement], acc: Set[ViewerNode]): Set[ViewerNode] =
      remaining match
        case Nil => acc

        case EdgeStmt(edgeList, _) :: tail =>
          val children = edgeList.flatMap:
            case n: DotNodeId          => List(NodeStmt(n, Nil))
            case Subgraph(children, _) => children
          loop(remaining = children ++ tail, acc = acc)

        case NodeStmt(nodeId, attr_list) :: tail =>
          loop(remaining = tail, acc = acc + ViewerNode.node(nodeId.id, attr_list))

        case Subgraph(children, _) :: tail => loop(remaining = children ++ tail, acc = acc)

        case _ :: tail => loop(remaining = tail, acc = acc)

    loop(List(graphElement), Set.empty)

  def findAllArrows: Set[Arrow] =
    @tailrec
    def loop(remaining: List[GraphElement], acc: Set[Arrow] = Set.empty): Set[Arrow] =
      remaining match
        case Nil => acc
        case h :: remaining1 =>
          h match
            case e: EdgeStmt =>
              val (remaining2, acc2) = e.allArrows1.unzip
              loop(remaining = remaining2.flatten ++ remaining1, acc = acc ++ acc2.toSet.flatten)

            case Subgraph(children, _) => loop(remaining = children ++ remaining1, acc = acc)
            case _                     => loop(remaining = remaining1, acc = acc)

    loop(List(graphElement))

  // TODO: make this tail recursive
  def removeGraphNodes(idsToRemove: Set[String]): List[GraphElement] =
    graphElement match
      case NodeStmt(DotNodeId(id, _), _) if id in idsToRemove => Nil

      case e @ EdgeStmt(edgeList, attrList) =>
        val eArrows: Set[String] = e.allArrows.map(_.nodeId.value)

        def prependToHead(e: EdgeElement, acc: List[List[EdgeElement]]) =
          acc match
            case Nil    => (e :: Nil) :: Nil
            case h :: t => (e :: h) :: t

        if eArrows.subsetOf(idsToRemove) then
          Nil
        else
          val remainingEdges =
            edgeList.foldLeft(Nil: List[List[EdgeElement]]):
              case (acc, e @ DotNodeId(id, _)) =>
                if id in idsToRemove then
                  Nil :: acc // start a new edge: [[]] OR [[], e1, e2, ...]
                else
                  prependToHead(e, acc) // [[e]] OR [e :: e1, e2, ...]

              case (acc, Subgraph(children, id)) =>
                val visibleChildren = children.flatMap(_.removeGraphNodes(idsToRemove))
                if visibleChildren.isEmpty then
                  Nil :: acc
                else
                  prependToHead(Subgraph(visibleChildren, id), acc)

          remainingEdges.filter(_.nonEmpty).reverse
            .map:
              case h :: Nil => h match
                  // Drop the attributes on purpose.
                  // Otherwise, the attributes will be attached to remaining node.
                  case n: DotNodeId => NodeStmt(n, List.empty)
                  case g: Subgraph  => g
              case other => EdgeStmt(other.reverse, attrList)

      case Subgraph(children, id) =>
        val remainingChildren = children.flatMap(_.removeGraphNodes(idsToRemove))
        if remainingChildren.isEmpty then Nil else List(Subgraph(remainingChildren, id))

      case other => List(other)
