package org.jpablo.typeexplorer.viewer.formats.dot.ast

import org.jpablo.typeexplorer.viewer.formats.dot.ast.Location.Position
import upickle.implicits.key
import upickle.default.*
import com.softwaremill.quicklens.*

case class DiGraphAST(location: Location, children: List[GraphElement], id: String) derives ReadWriter:

  def allNodesIds: Set[String] =
    def go(elems: List[GraphElement]): Set[String] =
      elems
        .collect:
          case NodeStmt(nodeId, _)   => Set(nodeId.id)
          case EdgeStmt(edgeList, _) => edgeList.map(_.id)
          case Subgraph(children, _) => go(children)
        .flatten
        .toSet

    go(children)

  def allArrows: Set[(String, String)] =
    def go(elems: List[GraphElement]): Set[(String, String)] =
      elems
        .collect:
          case EdgeStmt(edgeList, _) if edgeList.size >= 2 =>
            edgeList
              .sliding(2)
              .collect { case List(source, target) => (source.id, target.id) }
          case Subgraph(children, _) => go(children)
        .flatten
        .toSet
    go(children)

  def removeNodes(nodeIds: Set[String]): DiGraphAST =
//    dom.console.log(s"[DiGraphAST.removeNodes]")

    def remove(element: GraphElement): Option[GraphElement] =
      element match
        case e: EdgeStmt => Some(e.modify(_.edgeList).using(_.filterNot(n => nodeIds.contains(n.id))))
        case g: Subgraph => Some(g.modify(_.children).using(_.flatMap(remove)))
        case n: NodeStmt if nodeIds.contains(n.nodeId.id) => None
        case other                                        => Some(other)

    @annotation.tailrec
    def optimize(children: List[GraphElement], acc: List[GraphElement] = List.empty): List[GraphElement] =
      children match
        case h :: EdgeStmt(ids, _) :: t if ids.length < 2 => optimize(h :: t, acc)
        case Pad() :: Newline() :: t                      => optimize(t, acc)
        case h :: t                                       => optimize(t, h :: acc)
        case Nil                                          => acc.reverse

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
      .using(_.flatMap(remove))
      .modify(_.children)
      .using(optimize(_))
      .modify(_.children)
      .using(dedup)

  def render: String =
//    dom.console.log(s"[DiGraphAST.render]")
//    dom.console.log(write(this))
    def renderElement(element: GraphElement): String =
      element match
        case Newline() => "\n"

        case Pad() => " "

        case AttrStmt(target, attrList) =>
          val attrs = renderAttrList(attrList)
          if attrs.isEmpty then "" else s"$target $attrs"

        case EdgeStmt(edgeList, attrList) =>
          edgeList.map(n => s"\"${n.id}\"").mkString(" -> ") + renderAttrList(attrList)

        case StmtSep() => ""

        case NodeStmt(nodeId, attrList) =>
          "\"" + nodeId.id + "\"" + renderAttrList(attrList)

        case Comment() => ""

        case Subgraph(children, id) =>
          children.map(renderElement).mkString(s"subgraph ${id.getOrElse("")} {", "", "}")

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
    s"digraph \"${this.id}\" {$body}"
  end render

end DiGraphAST

case class Location(start: Position, end: Position) derives ReadWriter

object Location:
  case class Position(offset: Int, line: Int, column: Int) derives ReadWriter

@key("type")
sealed trait GraphElement derives ReadWriter

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
case class NodeStmt(@key("node_id") nodeId: DotNodeId, @key("attr_list") attrList: List[Attr]) extends GraphElement
    derives ReadWriter

@key("edge_stmt")
case class EdgeStmt(
    @key("edge_list") edgeList: List[DotNodeId],
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
case class Subgraph(children: List[GraphElement], id: Option[String]) extends GraphElement derives ReadWriter
