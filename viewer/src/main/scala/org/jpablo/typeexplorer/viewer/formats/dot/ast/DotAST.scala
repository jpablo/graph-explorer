package org.jpablo.typeexplorer.viewer.formats.dot.ast

import upickle.implicits.key
import upickle.default.*

case class DiGraph(
    location: Location,
    children: List[GraphElement],
    id:       String
) derives ReadWriter

case class Location(start: Position, end: Position) derives ReadWriter

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
case class Attr(
    location: Location,
    id:       String,
    @key("eq")
    attrEq: String
) derives ReadWriter

@key("edge_stmt")
case class EdgeStmt(
    location: Location,
    @key("edge_list")
    edgeList: List[NodeId],
    @key("attr_list")
    attrList: List[Attr]
) extends GraphElement
    derives ReadWriter

case class NodeId(
    location: Location,
    id:       String
) derives ReadWriter

@key("stmt_sep")
case class StmtSep(location: Location) extends GraphElement derives ReadWriter
