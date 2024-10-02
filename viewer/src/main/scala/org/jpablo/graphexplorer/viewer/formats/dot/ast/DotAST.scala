package org.jpablo.graphexplorer.viewer.formats.dot.ast

import org.jpablo.graphexplorer.viewer.formats.dot.ast.Location.Position
import upickle.implicits.key
import upickle.default.*
import com.softwaremill.quicklens.*

import scala.annotation.tailrec

case class DiGraphAST(children: List[GraphElement], id: Option[String] = None) derives ReadWriter:

  lazy val allNodesIds: Set[String] = findAllNodeIds(children)

  lazy val allArrows: Set[(String, String)] = findAllArrows(children)

  def removeNodes(idsToRemove: Set[String]): DiGraphAST =

    // TODO: make this tail recursive

    def removeFrom(element: GraphElement): List[GraphElement] =
      element match
        case NodeStmt(DotNodeId(id, _), _) if idsToRemove contains id => Nil

        case Subgraph(children, id) =>
          val remainingChildren = children.flatMap(removeFrom)
          if remainingChildren.isEmpty then Nil else List(Subgraph(remainingChildren, id))

        case EdgeStmt(edgeList, attrList) =>
          val remainingEdges: List[List[DotNodeId | Subgraph]] =
            edgeList.foldLeft(Nil):
              case (acc, n: DotNodeId) =>
                acc match
                  case _ if idsToRemove contains n.id => Nil :: acc
                  case Nil                            => (n :: Nil) :: Nil
                  case h :: t                         => (n :: h) :: t

              case (acc, Subgraph(children, id)) =>
                val visibleChildren = children.flatMap(removeFrom)
                if visibleChildren.isEmpty then Nil :: acc // remove the subgraph if it's empty
                else (Subgraph(visibleChildren, id) :: acc.headOption.getOrElse(Nil)) :: acc.tail

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

def findAllNodeIds(children: List[GraphElement]): Set[String] =
  children.toSet.flatMap(_.allNodesIds)

def findAllArrows(children: List[GraphElement]): Set[(String, String)] =
  children.toSet.flatMap(_.allArrows)

case class Location(start: Position, end: Position) derives ReadWriter

object Location:
  case class Position(offset: Int, line: Int, column: Int) derives ReadWriter

@key("type")
sealed trait GraphElement derives ReadWriter:

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

  lazy val allArrows: Set[(String, String)] =
    @tailrec
    def go(remaining: List[GraphElement], acc: Set[(String, String)] = Set.empty): Set[(String, String)] =
      remaining match
        case Nil => acc
        case h :: remaining1 =>
          h match
            case EdgeStmt(edgeList, _) =>
              val args: Iterator[(List[GraphElement], Set[(String, String)])] =
                edgeList
                  .sliding(2)
                  .map:
                    case List(Subgraph(children, _))                => (children, Set.empty)
                    case List(DotNodeId(id1, _), DotNodeId(id2, _)) => (Nil, Set(id1 -> id2))

                    case List(DotNodeId(id, _), Subgraph(children, _)) =>
                      (children, findAllNodeIds(children).map(a => id -> a))

                    case List(Subgraph(children, _), DotNodeId(id, _)) =>
                      (children, findAllNodeIds(children).map(a => a -> id))

                    case List(Subgraph(children1, _), Subgraph(children2, _)) =>
                      (
                        children1 ++ children2,
                        findAllNodeIds(children1).flatMap(a => findAllNodeIds(children2).map(b => a -> b))
                      )
                    case _ => (Nil, Set.empty)

              val (remaining2, acc1) = args.toList.unzip
              go(remaining2.flatten ++ remaining1, acc ++ acc1.toSet.flatten)
            case Subgraph(children, _) => go(children ++ remaining1, acc)
            case _                     => go(remaining1, acc)

    go(List(this))

end GraphElement

object GraphElement:
  given ReadWriter[DotNodeId | Subgraph] =
    readwriter[ujson.Value].bimap[DotNodeId | Subgraph](
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

@key("id")
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
    @key("edge_list") edgeList: List[DotNodeId | Subgraph],
    @key("attr_list") attrList: List[Attr]
) extends GraphElement
    derives ReadWriter

@key("node_id")
case class DotNodeId(id: String, port: Option[Port] = None) derives ReadWriter

@key("port")
case class Port(id: String) derives ReadWriter

@key("stmt_sep")
case class StmtSep() extends GraphElement derives ReadWriter

@key("subgraph")
case class Subgraph(children: List[GraphElement], id: Option[String] = None) extends GraphElement derives ReadWriter
