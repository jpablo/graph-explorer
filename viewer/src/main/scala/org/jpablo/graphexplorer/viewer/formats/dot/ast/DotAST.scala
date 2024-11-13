package org.jpablo.graphexplorer.viewer.formats.dot.ast

import org.jpablo.graphexplorer.viewer.formats.dot.ast.Location.Position
import org.jpablo.graphexplorer.viewer.models.Arrow
import org.jpablo.graphexplorer.viewer.models.Attributable.idAttributeKey
import upickle.default.*
import upickle.implicits.key

type EdgeElement = DotNodeId | Subgraph

case class DotAST(
    @key("type")
    tpe:      String,
    children: List[GraphElement],
    id:       Option[String] = None
) derives ReadWriter:
  lazy val allNodesIds: Set[String] = findAllNodeIds(children)
  lazy val allArrows: Set[Arrow] = findAllArrows(children)

object DotAST:
  val empty: DotAST = DotAST("digraph", Nil)

def findAllNodeIds(children: List[GraphElement]): Set[String] =
  children.toSet.flatMap(_.allNodesIds)

def findAllArrows(children: List[GraphElement]): Set[Arrow] =
  children.toSet.flatMap(_.allArrows)

case class Location(start: Position, end: Position) derives ReadWriter

object Location:
  case class Position(offset: Int, line: Int, column: Int) derives ReadWriter

@key("type")
sealed trait GraphElement derives ReadWriter:
  lazy val allNodesIds: Set[String] = this.findAllNodeIds1
  lazy val allArrows: Set[Arrow] = this.findAllArrows1

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
    node_id:   DotNodeId,
    attr_list: List[Attr]
) extends GraphElement derives ReadWriter

@key("edge_stmt")
case class EdgeStmt(
    edge_list: List[EdgeElement],
    attr_list: List[Attr]
) extends GraphElement derives ReadWriter:
  lazy val idAttr: String = attr_list.find(_.id == idAttributeKey).map(_.attrEq.toString).getOrElse("")

  def allArrows1: List[(List[GraphElement], Set[Arrow])] =
    // TODO: Handle AttrEq as well (for html labels)
    val attrs = attr_list.map(attr => attr.id -> attr.attrEq.toString).toMap
    edge_list
      .sliding(2)
      .toList
      .map:
        case List(Subgraph(children, _))                => (children, Set.empty)
        case List(DotNodeId(id1, _), DotNodeId(id2, _)) => (Nil, Set(Arrow(id1 -> id2, attrs)))

        case List(DotNodeId(id, _), Subgraph(children, _)) =>
          (children, findAllNodeIds(children).map(a => Arrow(id -> a, attrs)))

        case List(Subgraph(children, _), DotNodeId(id, _)) =>
          (children, findAllNodeIds(children).map(a => Arrow(a -> id, attrs)))

        case List(Subgraph(children1, _), Subgraph(children2, _)) =>
          (
            children1 ++ children2,
            for
              a <- findAllNodeIds(children1)
              b <- findAllNodeIds(children2)
            yield Arrow(a -> b, attrs)
          )
        case _ => (Nil, Set.empty)

object EdgeStmt:
  private var idx = 0

  def resetId() =
    idx = 0

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
