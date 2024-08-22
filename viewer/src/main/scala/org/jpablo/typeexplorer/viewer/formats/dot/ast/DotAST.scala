package org.jpablo.typeexplorer.viewer.formats.dot.ast

import org.jpablo.typeexplorer.viewer.formats.dot.ast.Location.Position
import upickle.implicits.key
import upickle.default.*
import com.softwaremill.quicklens.*
import org.scalajs.dom

case class DiGraphAST(location: Location, children: List[GraphElement], id: String) derives ReadWriter:

  private def _allNodesIds(elems: List[GraphElement]): Set[String] =
    elems
      .collect:
        case EdgeStmt(edgeList, _) => edgeList.map(_.id)
        case Subgraph(children, _) => _allNodesIds(children)
      .flatten
      .toSet

  def allNodesIds: Set[String] =
    _allNodesIds(children)

  private def _allArrows(elems: List[GraphElement]): Set[(String, String)] =
    elems
      .collect:
        case EdgeStmt(edgeList, _) if edgeList.size >= 2 =>
          edgeList
            .sliding(2)
            .collect { case List(source, target) => (source.id, target.id) }
        case Subgraph(children, _) => _allArrows(children)
      .flatten
      .toSet

  def allArrows: Set[(String, String)] =
    _allArrows(children)

  def removeNodes(nodeIds: Set[String]): DiGraphAST =
    dom.console.log(s"[DiGraphAST.removeNodes]")

    def remove(element: GraphElement): Option[GraphElement] =
      element match
        case e: EdgeStmt => Some(e.modify(_.edgeList).using(_.filterNot(n => nodeIds.contains(n.id))))
        case g: Subgraph => Some(g.modify(_.children).using(_.flatMap(remove)))
        case n: NodeStmt if nodeIds.contains(n.nodeId.id) => None
        case other                                        => Some(other)

    def optimize(lst: List[GraphElement]): List[GraphElement] =
      lst match
        case h :: EdgeStmt(Nil, _) :: t      => optimize(h :: t)
        case h :: EdgeStmt(_ :: Nil, _) :: t => optimize(h :: t)
        case Pad() :: Newline() :: t         => optimize(t)
        case h :: t                          => h :: optimize(t)
        case Nil                             => Nil

    this
      .modify(_.children)
      .using(_.flatMap(remove))
      .modify(_.children)
      .using(optimize)
//      .modify(_.children)
//      .using(_.distinct)

  def render: String =
    dom.console.log(s"[DiGraphAST.render]")
    dom.console.log(write(this))
    def renderElement(element: GraphElement): String =
      element match
        case Newline() => "\n"

        case Pad() => ""

        case AttrStmt(target, attrList) =>
          val attrs = renderAttrList(attrList)
          val x = if attrs.isEmpty then "" else s"$target $attrs"
          dom.console.log(s"target: $target, attrs: $attrs")
          x

        case EdgeStmt(edgeList, attrList) =>
          val x = edgeList.map(n => s"\"${n.id}\"").mkString(" -> ") + renderAttrList(attrList)
          dom.console.log(s"edge: $x")
          x

        case StmtSep() => ""

        case NodeStmt(nodeId, attrList) =>
          val x = "\"" + nodeId.id + "\"" + renderAttrList(attrList)
          dom.console.log(s"node: $x")
          x

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
              case Attr(id, s) => s"$id=\"$s\""
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
