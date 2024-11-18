package org.jpablo.graphexplorer.viewer.formats.dot.ast

import org.jpablo.graphexplorer.viewer.extensions.*
import org.jpablo.graphexplorer.viewer.models.{Arrow, Attributes}

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

  def findAllNodeIds: Set[String] =
    @tailrec
    def loop(remaining: List[GraphElement], acc: Set[String]): Set[String] =
      remaining match
        case Nil => acc
        case EdgeStmt(edgeList, _) :: tail =>
          val children = edgeList.flatMap:
            case n: DotNodeId          => List(NodeStmt(n, Nil))
            case Subgraph(children, _) => children
          loop(remaining = children ++ tail, acc = acc)
        case NodeStmt(nodeId, _) :: tail   => loop(remaining = tail, acc = acc + nodeId.id)
        case Subgraph(children, _) :: tail => loop(remaining = children ++ tail, acc = acc)
        case _ :: tail                     => loop(remaining = tail, acc = acc)
    loop(List(graphElement), Set.empty)

  def toAttributes(attrList: List[Attr]): Attributes =
    Attributes(attrList.map(attr => attr.id -> attr.value).toMap)

  def findAllNodeAttributes(nodeIds: Set[String]): Set[Attributes] =
    @tailrec
    def loop(remaining: List[GraphElement], acc: Set[Attributes]): Set[Attributes] =
      remaining match
        case Nil => acc
        case EdgeStmt(edgeList, _) :: tail =>
          val children = edgeList.collect { case Subgraph(c, _) => c }.flatten
          loop(remaining = children ++ tail, acc = acc)

        case NodeStmt(DotNodeId(id, _), attr_list) :: tail if id in nodeIds =>
          loop(remaining = tail, acc = acc + toAttributes(attr_list))
        case Subgraph(children, _) :: tail => loop(remaining = children ++ tail, acc = acc)
        case _ :: tail                     => loop(remaining = tail, acc = acc)
    loop(List(graphElement), Set.empty)

  def findAllArrows1: Set[Arrow] =
    @tailrec
    def loop(remaining: List[GraphElement], acc: Set[Arrow] = Set.empty): Set[Arrow] =
      remaining match
        case Nil => acc
        case h :: remaining1 =>
          h match
            case e: EdgeStmt =>
              val (remaining2, acc1) = e.allArrows1.unzip
              loop(remaining2.flatten ++ remaining1, acc ++ acc1.toSet.flatten)

            case Subgraph(children, _) => loop(children ++ remaining1, acc)
            case _                     => loop(remaining1, acc)

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
