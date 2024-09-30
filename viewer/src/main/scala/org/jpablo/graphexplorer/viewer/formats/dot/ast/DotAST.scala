package org.jpablo.graphexplorer.viewer.formats.dot.ast

import org.jpablo.graphexplorer.viewer.formats.dot.ast.Location.Position
import upickle.implicits.key
import upickle.default.*
import com.softwaremill.quicklens.*

case class DiGraphAST(children: List[GraphElement], id: Option[String] = None) derives ReadWriter:

  def allNodesIds: Set[String] =
    def go(elems: List[GraphElement]): Set[String] =
      elems
        .collect:
          case NodeStmt(nodeId, _) => Set(nodeId.id)
          case EdgeStmt(edgeList, _) =>
            edgeList
              .flatMap:
                case DotNodeId(id, _)      => Set(id)
                case Subgraph(children, _) => go(children)
              .toSet
          case Subgraph(children, _) => go(children)
        .flatten
        .toSet

    go(children)

  def allArrows: Set[(String, String)] =
    // TODO: make this tail recursive
    def go(elems: List[GraphElement]): Set[(String, String)] =
      elems
        .collect:
          case EdgeStmt(edgeList, _) =>
            edgeList.sliding(2).foldLeft(Set.empty[(String, String)]) {

              case (acc, List(Subgraph(children, _))) => go(children) ++ acc

              case (acc, List(DotNodeId(id1, _), DotNodeId(id2, _))) => Set(id1 -> id2) ++ acc

              case (acc, List(DotNodeId(id, _), Subgraph(children, _))) =>
                val childrenGraph = DiGraphAST(children)
                val arrows1 = childrenGraph.allArrows
                val nodeIds1 = childrenGraph.allNodesIds
                nodeIds1.map(a => id -> a) ++ arrows1 ++ acc

              case (acc, List(Subgraph(children, _), DotNodeId(id, _))) =>
                val childrenGraph = DiGraphAST(children)
                val arrows1 = childrenGraph.allArrows
                val nodeIds1 = childrenGraph.allNodesIds
                nodeIds1.map(a => a -> id) ++ arrows1 ++ acc

              case (acc, List(Subgraph(children1, _), Subgraph(children2, _))) =>
                val arrows1 = DiGraphAST(children1).allArrows
                val arrows2 = DiGraphAST(children2).allArrows
                val nodeIds1 = DiGraphAST(children1).allNodesIds
                val nodeIds2 = DiGraphAST(children2).allNodesIds
                nodeIds1.flatMap(a => nodeIds2.map(b => a -> b)) ++ arrows1 ++ arrows2 ++ acc
              case (acc, _) => acc
            }

          case Subgraph(children, _) => go(children)
        .flatten
        .toSet

    go(children)

  def removeNodes(idsToRemove: Set[String]): DiGraphAST =

    def removeFrom(element: GraphElement): Option[GraphElement] =
      element match
        case NodeStmt(DotNodeId(id, _), _) if idsToRemove contains id => None

        case Subgraph(children, id) =>
          val remainingChildren = children.flatMap(removeFrom)
          if remainingChildren.isEmpty then None else Some(Subgraph(remainingChildren, id))

        case EdgeStmt(edgeList, attrList) =>
          val remainingEdges = edgeList.flatMap {
            case DotNodeId(id, _) if idsToRemove contains id => None
            case Subgraph(children, id) =>
              val remainingChildren = children.flatMap(removeFrom)
              if remainingChildren.isEmpty then None else Some(Subgraph(remainingChildren, id))
            case other => Some(other)
          }
          if remainingEdges.isEmpty then None else Some(EdgeStmt(remainingEdges, attrList))

        case other => Some(other)

    @annotation.tailrec
    def optimize(acc: List[GraphElement] = Nil, children: List[GraphElement]): List[GraphElement] =
      children match
        case h :: EdgeStmt(edges, _) :: t if edges.isEmpty => optimize(acc, h :: t)
        case Pad() :: Newline() :: t                       => optimize(acc, t)
        case h :: t                                        => optimize(h :: acc, t)
        case Nil                                           => acc.reverse

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

case class Location(start: Position, end: Position) derives ReadWriter

object Location:
  case class Position(offset: Int, line: Int, column: Int) derives ReadWriter

@key("type")
sealed trait GraphElement derives ReadWriter

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
