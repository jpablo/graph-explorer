package org.jpablo.graphexplorer.viewer.models

import org.jpablo.graphexplorer.viewer.utils.Utils.randomUUIDSafe
import upickle.default.*

sealed trait ElementId derives CanEqual, ReadWriter:
  def value: String

  def isGroupId: Boolean = this match { case _: GroupId => true; case _ => false }
  def isNodeId: Boolean  = this match { case _: NodeId => true; case _ => false }
  def isArrowId: Boolean = this match { case _: ArrowId => true; case _ => false }

  def asNodeId: Option[NodeId]   = this match { case id: NodeId => Some(id); case _ => None }
  def asArrowId: Option[ArrowId] = this match { case id: ArrowId => Some(id); case _ => None }
  def asGroupId: Option[GroupId] = this match { case id: GroupId => Some(id); case _ => None }

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

object ArrowId:
  given ReadWriter[ArrowId] = stringKeyRW(readwriter[String].bimap[ArrowId](_.value, ArrowId(_)))

object GroupId:
  val clusterId = raw"cluster_(.+)".r

  def fromDot(cluster: String): (GroupId, Boolean) = cluster match
    case clusterId(id) => GroupId(id)      -> true
    case _             => GroupId(cluster) -> false

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
