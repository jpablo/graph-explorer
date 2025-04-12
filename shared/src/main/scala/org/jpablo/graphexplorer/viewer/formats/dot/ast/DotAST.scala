package org.jpablo.graphexplorer.viewer.formats.dot.ast

import org.jpablo.graphexplorer.viewer.formats.dot.ast.Location.Position
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.GraphType.digraph
import org.jpablo.graphexplorer.viewer.graph.ViewerGraphElements
import org.jpablo.graphexplorer.viewer.models.{AttributeId, Attributes}
import org.jpablo.graphexplorer.viewer.models.ViewerElement.idAttributeKey
import org.jpablo.graphexplorer.viewer.utils.Utils.randomUUIDSafe
import upickle.default.*
import upickle.implicits.key

type EdgeElement = DotNodeId | SubGraph

case class DotAST(
    @key("type")
    tpe:      String,
    children: List[GraphElement],
    id:       Option[String] = None
) derives ReadWriter, CanEqual:
  def toSubGraph: SubGraph = SubGraph(children, id)

object DotAST:
  val empty: DotAST = DotAST(digraph.toString, Nil, id = Some(ViewerGraphElements.defaultRootId.value))

case class Location(start: Position, end: Position) derives ReadWriter

object Location:
  case class Position(offset: Int, line: Int, column: Int) derives ReadWriter

@key("type")
sealed trait GraphElement derives ReadWriter:
  lazy val allNodesIds: List[String] = this.findAllViewerNodes.keys.toList.map(_.value)
  def nodeId = this match
    case NodeStmt(node_id, _) => Some(node_id.id)
    case SubGraph(_, id)      => id
    case _                    => None

object GraphElement:

  given ReadWriter[EdgeElement] =
    readwriter[ujson.Value].bimap[EdgeElement](
      {
        case s: DotNodeId => writeJs(s)
        case a: SubGraph  => writeJs(a)
      },
      { jsValue =>
        if jsValue("type").str == "node_id" then read[DotNodeId](jsValue)
        else read[SubGraph](jsValue)
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
case class AttrStmt(target: String, attr_list: List[Attr]) extends GraphElement derives ReadWriter

case class AttrValue(value: String | AttrEq) derives CanEqual:
  override def toString: String = value match
    case s: String => s
    case a: AttrEq => a.value

  // hack
  def isTrue: Boolean =
    this.toString == true.toString

object AttrValue:
  val empty = AttrValue("")

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

  given ReadWriter[AttrValue] =
    readwriter[String | AttrEq].bimap[AttrValue](_.value, AttrValue(_))

end AttrValue

@key("attr")
case class Attr(id: String, @key("eq") attrEq: AttrValue) derives ReadWriter

object Attr:
  def apply(id: String, value: String): Attr = Attr(id, AttrValue(value))
end Attr

case class AttrEq(value: String, html: Boolean = false) derives ReadWriter

@key("node_stmt")
case class NodeStmt(node_id: DotNodeId, attr_list: List[Attr] = Nil) extends GraphElement derives ReadWriter

@key("edge_stmt")
case class EdgeStmt(
    edge_list: List[EdgeElement],
    attr_list: List[Attr] = Nil
) extends GraphElement derives ReadWriter:
  lazy val idAttr: Option[String] = attr_list.find(_.id == idAttributeKey.value).map(_.attrEq.toString)

  def toGraphElements: List[GraphElement] =
    edge_list.flatMap:
      case n: DotNodeId          => NodeStmt(n, Nil) :: Nil
      case SubGraph(children, _) => children

object EdgeStmt:
  private var idx = 0

  def resetId() =
    idx = 0

  def nextId() =
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
case class SubGraph(children: List[GraphElement], id: Option[String] = None)
    extends GraphElement derives ReadWriter:
  def collectAttributesByTarget: Map[AttributeTarget, Attributes] =
    children
      .collect:
        case AttrStmt(target, attrs) =>
          val targetEnum = AttributeTarget.valueOf(target.toLowerCase)
          (targetEnum, attrs.map(attr => AttributeId(attr.id) -> attr.attrEq).toMap)
      .groupBy(_._1)
      .view
      .mapValues(tuples => Attributes(tuples.flatMap(_._2).toMap))
      .toMap

object SubGraph:
  // TODO: find a better way to generate unique ids
  def randomId(): String = "g" + randomUUIDSafe().take(8)

def toAttrsMap(attrList: List[Attr]): Map[AttributeId, AttrValue] =
  attrList.map(attr => AttributeId(attr.id) -> attr.attrEq).toMap
