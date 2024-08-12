package org.jpablo.typeexplorer.viewer.formats.dot.ast

import org.jpablo.typeexplorer.viewer.formats.dot.ast.Location.Position
import upickle.implicits.key
import upickle.default.*
import com.softwaremill.quicklens.*
import org.scalajs.dom

case class DiGraphAST(location: Location, children: List[GraphElement], id: String) derives ReadWriter:

  def allNodesIds: Set[String] =
    children
      .collect { case EdgeStmt(_, edgeList, _) => edgeList.map(_.id) }
      .flatten
      .toSet

  def allArrows: Set[(String, String)] =
    children
      .collect:
        case EdgeStmt(_, edgeList, _) if edgeList.size >= 2 =>
          edgeList
            .sliding(2)
            .collect { case List(source, target) => (source.id, target.id) }
      .flatten
      .toSet

  def removeNodes(nodeIds: Set[String]): DiGraphAST =
    dom.console.log("DotAST] removeNodes")
    this
      .modify(_.children.each.when[EdgeStmt].edgeList)
      .using(_.filterNot(n => nodeIds.contains(n.id)))

  def render: String = {
    dom.console.log("[DotAST] render")
    def graphElement(element: GraphElement): String =
      element match
        case Newline(_) => "\n"

        case Pad(_) => " "

        case AttrStmt(_, target, attrList) =>
          val attrs = buildAttrList(attrList)
          if attrs.isEmpty then "" else s"$target $attrs"

        case EdgeStmt(_, edgeList, attrList) =>
          val edges = edgeList.map(_.id).mkString("\"", "\" -> \"", "\"")
          edges + buildAttrList(attrList)

        case StmtSep(_) => ""

        case NodeStmt(_, nodeId, attrList) =>
          "\"" + nodeId.id + "\"" + buildAttrList(attrList)

        case Comment(_) => ""

        case Subgraph(_, children) =>
          children.map(graphElement).mkString("{", "", "}")

    def buildAttrList(attrList: List[Attr]): String =
      val r = attrList.map(attr => s"${attr.id}=\"${attr.attrEq}\"")
      if r.isEmpty then "" else r.mkString(" [", ", ", "];")

    val header = s"digraph ${this.id} {"
    val body = this.children
      .map(graphElement)
      .filter(_.nonEmpty)
      .mkString("")
    val footer = "}"

    header + body + footer
  }

end DiGraphAST

case class Location(start: Position, end: Position) derives ReadWriter

object Location:
  case class Position(offset: Int, line: Int, column: Int) derives ReadWriter

@key("type")
sealed trait GraphElement derives ReadWriter:
  def location: Location

@key("newline")
case class Newline(location: Location) extends GraphElement derives ReadWriter

@key("pad")
case class Pad(location: Location) extends GraphElement derives ReadWriter

@key("comment")
case class Comment(location: Location) extends GraphElement derives ReadWriter

@key("attr_stmt")
case class AttrStmt(
    location:                   Location,
    target:                     String,
    @key("attr_list") attrList: List[Attr]
) extends GraphElement
    derives ReadWriter

@key("attr")
case class Attr(location: Location, id: String, @key("eq") attrEq: String | AttrEq) derives ReadWriter

//object AttrEqSting:
//  given ReadWriter[AttrEqSting] = readwriter[String].bimap[AttrEqSting](_.value, AttrEqSting(_))
//end AttrEqSting

@key("id")
case class AttrEq(
    location: Location,
    value:    String,
    html:     Boolean = false
) derives ReadWriter

object Attr:

  given ReadWriter[String | AttrEq] =
    readwriter[ujson.Value].bimap[String | AttrEq](
      {
        case s: String => ujson.Str(s)
        case a: AttrEq => writeJs(a)
      },
      {
        case ujson.Str(s) => s
        case json         => read[AttrEq](json)
      }
    )

end Attr

@key("node_stmt")
case class NodeStmt(
    location:                   Location,
    @key("node_id") nodeId:     DotNodeId,
    @key("attr_list") attrList: List[Attr]
) extends GraphElement
    derives ReadWriter

@key("edge_stmt")
case class EdgeStmt(
    location:                   Location,
    @key("edge_list") edgeList: List[DotNodeId],
    @key("attr_list") attrList: List[Attr]
) extends GraphElement
    derives ReadWriter

@key("node_id")
case class DotNodeId(location: Location, id: String, port: Option[Port] = None) derives ReadWriter

@key("port")
case class Port(location: Location, id: String) derives ReadWriter

@key("stmt_sep")
case class StmtSep(location: Location) extends GraphElement derives ReadWriter

@key("subgraph")
case class Subgraph(location: Location, children: List[GraphElement]/*, id: String*/) extends GraphElement
    derives ReadWriter
