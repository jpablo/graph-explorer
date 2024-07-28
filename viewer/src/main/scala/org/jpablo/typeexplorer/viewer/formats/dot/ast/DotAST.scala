package org.jpablo.typeexplorer.viewer.formats.dot.ast

import org.jpablo.typeexplorer.viewer.formats.dot.ast.Location.Position
import upickle.implicits.key
import upickle.default.*
import com.softwaremill.quicklens.*

case class DiGraph(location: Location, children: List[GraphElement], id: String) derives ReadWriter:
  def allNodesIds: Set[String] =
    children
      .collect { case EdgeStmt(_, edgeList, _) => edgeList.map(_.id) }
      .flatten
      .toSet

  def allArrows: Set[(String, String)] =
    children
      .collect {
        case EdgeStmt(_, edgeList, _) if edgeList.size >= 2 =>
          edgeList
            .sliding(2)
            .collect { case List(source, target) =>
              (source.id, target.id)
            }
      }
      .flatten
      .toSet

  def removeNodes(nodeIds: Set[String]): DiGraph =
    this
      .modify(_.children.each.when[EdgeStmt].edgeList)
      .using(_.filterNot(n => nodeIds.contains(n.id)))

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

@key("attr_stmt")
case class AttrStmt(
    location: Location,
    target:   String,
    @key("attr_list")
    attrList: List[Attr]
) extends GraphElement
    derives ReadWriter

@key("attr")
case class Attr(location: Location, id: String, @key("eq") attrEq: String) derives ReadWriter

@key("edge_stmt")
case class EdgeStmt(
    location: Location,
    @key("edge_list")
    edgeList: List[EdgeStmt.DotNodeId],
    @key("attr_list")
    attrList: List[Attr]
) extends GraphElement
    derives ReadWriter

object EdgeStmt:
  case class DotNodeId(location: Location, id: String) derives ReadWriter

@key("stmt_sep")
case class StmtSep(location: Location) extends GraphElement derives ReadWriter
