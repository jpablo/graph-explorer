package org.jpablo.typeexplorer.viewer.formats.dot.ast

import upickle.implicits.key
import upickle.default.*

//case class DotAST(elements: List[DiGraph])
//
//object DotAST:
//  given JsonValueCodec[DotAST] = JsonCodecMaker.make
//end DotAST

case class DiGraph(
    location: Location,
    children: List[GraphElement],
    id:       String
) derives ReadWriter

case class Location(start: Position, end: Position) derives ReadWriter

case class Position(offset: Int, line: Int, column: Int) derives ReadWriter

sealed trait GraphElement derives ReadWriter:
  def location: Location

case class Newline(location: Location) extends GraphElement derives ReadWriter

case class Pad(location: Location) extends GraphElement derives ReadWriter

case class AttrStmt(
    location: Location,
    target:   String,
    attrList: List[Attr]
) extends GraphElement
    derives ReadWriter

case class Attr(
    location: Location,
    id:       String,
    @key("eq")
    attrEq: String
) derives ReadWriter

case class EdgeStmt(
    location: Location,
    edgeList: List[NodeId],
    attrList: List[Attr]
) extends GraphElement
    derives ReadWriter

case class NodeId(
    location: Location,
    id:       String
) derives ReadWriter

case class StmtSep( /*`type`: String, */ location: Location) extends GraphElement derives ReadWriter
