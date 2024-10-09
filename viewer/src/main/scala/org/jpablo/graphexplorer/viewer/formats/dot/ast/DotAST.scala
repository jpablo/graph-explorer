package org.jpablo.graphexplorer.viewer.formats.dot.ast

import com.softwaremill.quicklens.*
import org.jpablo.graphexplorer.viewer.formats.dot.ast.Location.Position
import org.jpablo.graphexplorer.viewer.models.Arrow
import upickle.default.*
import upickle.implicits.key

import scala.annotation.tailrec

type EdgeElement = DotNodeId | Subgraph

case class DiGraphAST(children: List[GraphElement], id: Option[String] = None) derives ReadWriter:

  lazy val allNodesIds: Set[String] = findAllNodeIds(children)

  lazy val allArrows: Set[Arrow] = findAllArrows(children)

  // add an attribute [id=nextId] to all edges
  def attachIds: DiGraphAST =
    this.modify(_.children).using(_.map(_.attachId))

  def removeNodes(idsToRemove: Set[String]): DiGraphAST =

    // TODO: make this tail recursive

    def removeFrom(element: GraphElement): List[GraphElement] =
      element match
        case NodeStmt(DotNodeId(id, _), _) if idsToRemove contains id => Nil

        case Subgraph(children, id) =>
          val remainingChildren = children.flatMap(removeFrom)
          if remainingChildren.isEmpty then Nil else List(Subgraph(remainingChildren, id))

        case EdgeStmt(edgeList, attrList) =>
          def insertEdgeElem(acc: List[List[EdgeElement]], edgeElem: EdgeElement) =
            acc match
              case Nil    => (edgeElem :: Nil) :: Nil
              case h :: t => (edgeElem :: h) :: t

          val remainingEdges: List[List[EdgeElement]] =
            edgeList.foldLeft(Nil):
              case (acc, edgeElem: DotNodeId) =>
                if idsToRemove contains edgeElem.id then Nil :: acc
                else insertEdgeElem(acc, edgeElem)

              case (acc, Subgraph(children, id)) =>
                val visibleChildren = children.flatMap(removeFrom)
                if visibleChildren.isEmpty then Nil :: acc
                else insertEdgeElem(acc, edgeElem = Subgraph(visibleChildren, id))

          remainingEdges.reverse.map(es => EdgeStmt(es.reverse, attrList))

        case other => List(other)

    @tailrec
    def optimize(acc: List[GraphElement] = Nil, children: List[GraphElement]): List[GraphElement] =
      children match
        case h :: EdgeStmt(Nil, _) :: t => optimize(acc, h :: t)
        case Pad() :: Newline() :: t    => optimize(acc, t)
        case h :: t                     => optimize(h :: acc, t)
        case Nil                        => acc.reverse

    def dedup(lst: List[GraphElement]): List[GraphElement] =
      lst
        .foldLeft((List.empty[GraphElement], Set.empty[GraphElement])):
          case ((acc, visited), e: EdgeStmt) if visited.contains(e) => (acc, visited)
          case ((acc, visited), n: NodeStmt) if visited.contains(n) => (acc, visited)
          case ((acc, visited), e)                                  => (e :: acc, visited + e)
        ._1
        .reverse

    this
      .modify(_.children)
      .using(_.flatMap(removeFrom))
      .modify(_.children)
      .using(optimize(Nil, _))
      .modify(_.children)
      .using(dedup)

  def render: String =
    def renderElement(element: GraphElement): String =
      element match
        case Newline() => "\n"

        case Pad() => " "

        case AttrStmt(target, attrList) =>
          val attrs = renderAttrList(attrList)
          if attrs.isEmpty then "" else s"$target $attrs"

        case EdgeStmt(edgeList, attrList) =>
          edgeList
            .map:
              case n: DotNodeId => s"\"${n.id}\""
              case s: Subgraph  => renderElement(s)
            .mkString(" -> ") + renderAttrList(attrList)

        case StmtSep() => ""

        case NodeStmt(nodeId, attrList) =>
          "\"" + nodeId.id + "\"" + renderAttrList(attrList)

        case Comment() => ""

        case Subgraph(children, id) =>
          children.map(renderElement).mkString(s"subgraph ${id.getOrElse("")}{", "", "}")

    def renderAttrList(attrList: List[Attr]): String =
      attrList match
        case Nil => ""
        case attrs =>
          attrs
            .map:
              case Attr(id, AttrEq(value, html)) =>
                if html then s"$id=<$value>"
                else s"$id=\"$value\""
              case Attr("style", "stroke-dasharray: 5,5") => s"style=\"dashed\""
              case Attr(id, s: String)                    => s"$id=\"$s\""
            .mkString(" [", ", ", "];")

    val body = this.children
      .map(renderElement)
      .filter(_.nonEmpty)
      .mkString("")
    s"digraph ${id.map(id => s"\"$id\" ").getOrElse(" ")}{$body}"
  end render

end DiGraphAST

object DiGraphAST:
  val empty: DiGraphAST = DiGraphAST(Nil)
end DiGraphAST

def findAllNodeIds(children: List[GraphElement]): Set[String] =
  children.toSet.flatMap(_.allNodesIds)

def findAllArrows(children: List[GraphElement]): Set[Arrow] =
  children.toSet.flatMap(_.allArrows)

case class Location(start: Position, end: Position) derives ReadWriter

object Location:
  case class Position(offset: Int, line: Int, column: Int) derives ReadWriter

@key("type")
sealed trait GraphElement derives ReadWriter:

  // add an attribute [id=$nextId] to all edges
  def attachId: GraphElement =
    this match

      case EdgeStmt(edgeList, attrList) =>
        val edgeListWithIds = edgeList.map:
          case Subgraph(children, id) => Subgraph(children.map(_.attachId), id)
          case other                  => other

        EdgeStmt(edgeListWithIds, Attr("id", EdgeStmt.nextId.toString) :: attrList)

      case Subgraph(children, id) => Subgraph(children.map(_.attachId), id)
      case other                  => other

  lazy val allNodesIds: Set[String] =
    @tailrec
    def go(remaining: List[GraphElement], acc: Set[String]): Set[String] =
      remaining match
        case Nil => acc
        case h :: t =>
          h match
            case NodeStmt(nodeId, _) => go(t, acc + nodeId.id)
            case EdgeStmt(edgeList, _) =>
              val newElements =
                edgeList
                  .flatMap:
                    case n: DotNodeId          => List(NodeStmt(n, Nil))
                    case Subgraph(children, _) => children
              go(newElements ++ t, acc)
            case Subgraph(children, _) => go(children ++ t, acc)
            case _                     => go(t, acc)

    go(List(this), Set.empty)

  lazy val allArrows: Set[Arrow] =
    @tailrec
    def go(remaining: List[GraphElement], acc: Set[Arrow] = Set.empty): Set[Arrow] =
      remaining match
        case Nil => acc
        case h :: remaining1 =>
          h match
            case e @ EdgeStmt(edgeList, attrList) =>
              val attrs = attrList.map(attr => attr.id -> attr.attrEq.toString).toMap
              val args: Iterator[(List[GraphElement], Set[Arrow])] =
                edgeList
                  .sliding(2)
                  .map:
                    case List(Subgraph(children, _))                => (children, Set.empty)
                    case List(DotNodeId(id1, _), DotNodeId(id2, _)) => (Nil, Set(Arrow(id1 -> id2, e.idAttr, attrs)))

                    case List(DotNodeId(id, _), Subgraph(children, _)) =>
                      (children, findAllNodeIds(children).map(a => Arrow(id -> a, e.idAttr, attrs)))

                    case List(Subgraph(children, _), DotNodeId(id, _)) =>
                      (children, findAllNodeIds(children).map(a => Arrow(a -> id, e.idAttr, attrs)))

                    case List(Subgraph(children1, _), Subgraph(children2, _)) =>
                      (
                        children1 ++ children2,
                        findAllNodeIds(children1).flatMap(a =>
                          findAllNodeIds(children2).map(b => Arrow(a -> b, e.idAttr, attrs))
                        )
                      )
                    case _ => (Nil, Set.empty)

              val (remaining2, acc1) = args.toList.unzip

              go(remaining2.flatten ++ remaining1, acc ++ acc1.toSet.flatten)
            case Subgraph(children, _) => go(children ++ remaining1, acc)
            case _                     => go(remaining1, acc)

    go(List(this))

end GraphElement

object GraphElement:
  given ReadWriter[EdgeElement] =
    readwriter[ujson.Value].bimap[EdgeElement](
      {
        case s: DotNodeId => writeJs(s)
        case a: Subgraph  => writeJs(a)
      },
      { jsValue =>
        if jsValue("type") == ujson.Str("node_id") then read[DotNodeId](jsValue)
        else read[Subgraph](jsValue)
      }
    )
end GraphElement

@key("newline")
case class Newline() extends GraphElement derives ReadWriter

@key("pad")
case class Pad() extends GraphElement derives ReadWriter

@key("comment")
case class Comment() extends GraphElement derives ReadWriter

@key("attr_stmt")
case class AttrStmt(target: String, @key("attr_list") attrList: List[Attr]) extends GraphElement derives ReadWriter

@key("attr")
case class Attr(id: String, @key("eq") attrEq: String | AttrEq) derives ReadWriter

case class AttrEq(value: String, html: Boolean = false) derives ReadWriter

object Attr:
  given ReadWriter[String | AttrEq] =
    readwriter[ujson.Value].bimap[String | AttrEq](
      {
        case s: String => writeJs(s)
        case a: AttrEq => writeJs(a)
      },
      {
        case ujson.Str(s) => s
        case jsValue      => read[AttrEq](jsValue)
      }
    )
end Attr

@key("node_stmt")
case class NodeStmt(
    @key("node_id") nodeId:     DotNodeId,
    @key("attr_list") attrList: List[Attr]
) extends GraphElement
    derives ReadWriter

@key("edge_stmt")
case class EdgeStmt(
    @key("edge_list") edgeList: List[EdgeElement],
    @key("attr_list") attrList: List[Attr]
) extends GraphElement
    derives ReadWriter:
  def idAttr: Option[String] =
    attrList
      .flatMap:
        case Attr("id", value: String) => Some(value)
        case _                         => None
      .headOption

object EdgeStmt:
  private var idx = 0

  def nextId =
    idx += 1
    idx

end EdgeStmt

@key("node_id")
case class DotNodeId(id: String, port: Option[Port] = None) derives ReadWriter

@key("port")
case class Port(id: String) derives ReadWriter

@key("stmt_sep")
case class StmtSep() extends GraphElement derives ReadWriter

@key("subgraph")
case class Subgraph(children: List[GraphElement], id: Option[String] = None) extends GraphElement derives ReadWriter
