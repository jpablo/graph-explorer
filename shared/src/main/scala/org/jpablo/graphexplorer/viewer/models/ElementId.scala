package org.jpablo.graphexplorer.viewer.models

import org.jpablo.graphexplorer.viewer.utils.Utils.randomUUIDSafe
import upickle.default.*

/** Base trait for identifying graph elements (nodes, arrows, groups).
  *
  * Provides type-safe element identification with conversion between core values and SVG attribute formats. Each element type has a
  * specific prefix when serialized to SVG (e.g., "node:id", "arrow:id", "group:id").
  */
sealed trait ElementId derives CanEqual, ReadWriter:
  def value: String

  def isGroupId: Boolean = this match { case _: GroupId => true; case _ => false }
  def isNodeId: Boolean  = this match { case _: NodeId => true; case _ => false }
  def isArrowId: Boolean = this match { case _: ArrowId => true; case _ => false }
  def isRecordCellId: Boolean = this match { case _: RecordCellId => true; case _ => false }

  def asNodeId: Option[NodeId]   = this match { case id: NodeId => Some(id); case _ => None }
  def asArrowId: Option[ArrowId] = this match { case id: ArrowId => Some(id); case _ => None }
  def asGroupId: Option[GroupId] = this match { case id: GroupId => Some(id); case _ => None }
  def asRecordCellId: Option[RecordCellId] = this match { case id: RecordCellId => Some(id); case _ => None }

  def toSvg: String

sealed trait GroupMemberId extends ElementId derives ReadWriter

object GroupMemberId:
  def classify(ids: Set[GroupMemberId]): IdsByKind =
    ids.foldLeft(IdsByKind()): (acc, eId) =>
      eId match
        case id: GroupId => acc.copy(groups = acc.groups + id)
        case id: NodeId  => acc.copy(nodes = acc.nodes + id)

case class GroupId(value: String) extends GroupMemberId derives CanEqual:

  override def toString: String = value

  def toDot: String = s"$value"
  def toSvg: String = s"group:$value"

case class NodeId(value: String) extends GroupMemberId:
  override def toString: String = value

  def toSvg: String = s"node:$value"

case class ArrowId(value: String) extends ElementId:
  override def toString: String = value

  def toSvg: String = s"arrow:$value"

case class RecordCellId(nodeId: NodeId, port: String) extends ElementId:
  override def toString: String = s"${nodeId.value}:$port"

  def value: String = toString

  def toSvg: String = s"cell:${nodeId.value}:$port"

object ArrowId:
  given ReadWriter[ArrowId] = stringKeyRW(readwriter[String].bimap[ArrowId](_.value, ArrowId(_)))

  val arrowId = raw"arrow:(.+)".r

  def fromSvg(idAttr: String): Option[ArrowId] =
    idAttr match
      case arrowId(seq) => Some(ArrowId(seq))
      case _            => None

object GroupId:
  val clusterId = raw"cluster(.+)".r

  given ReadWriter[GroupId] = readwriter[String].bimap[GroupId](_.value, GroupId(_))

  def fromDot(cluster: String): (GroupId, Boolean) = cluster match
    case clusterId(id) =>
      // Drop leading underscore if present (e.g., cluster_0 -> 0, not _0)
      val cleanId = if (id.startsWith("_")) id.drop(1) else id
      GroupId(cleanId) -> true
    case _ => GroupId(cluster) -> false

  val groupId = raw"group:(.+)".r

  def fromSvg(idAttr: String): Option[GroupId] = idAttr match
    case groupId(seq) => Some(GroupId(seq))
    case _            => None

object NodeId:
  given ReadWriter[NodeId] = stringKeyRW(readwriter[String].bimap[NodeId](_.value, NodeId(_)))

  def random(): NodeId = NodeId(randomUUIDSafe().take(8))

  val nodeId = raw"node:(.+)".r

  def fromSvg(idAttr: String): Option[NodeId] =
    idAttr match
      case nodeId(seq) => Some(NodeId(seq))
      case _           => None

object RecordCellId:
  given ReadWriter[RecordCellId] = readwriter[ujson.Value].bimap[RecordCellId](
    cell => ujson.Obj("nodeId" -> cell.nodeId.value, "port" -> cell.port),
    json => RecordCellId(NodeId(json("nodeId").str), json("port").str)
  )

  val cellId = raw"cell:([^:]+):(.+)".r

  def fromSvg(idAttr: String): Option[RecordCellId] =
    idAttr match
      case cellId(nodeId, port) => Some(RecordCellId(NodeId(nodeId), port))
      case _                    => None
