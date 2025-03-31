package org.jpablo.graphexplorer.viewer.models

import org.jpablo.graphexplorer.viewer.utils.Utils.randomUUIDSafe
import upickle.default.*

import scala.compiletime.asMatchable

sealed trait ElementId derives CanEqual, ReadWriter:
  def value: String

  def isGroupId: Boolean = this match { case _: GroupId => true; case _ => false }
  def isNodeId: Boolean = this match { case _: NodeId => true; case _ => false }
  def isArrowId: Boolean = this match { case _: ArrowId => true; case _ => false }

  def asNodeId: Option[NodeId] = this match { case id: NodeId => Some(id); case _ => None }
  def asArrowId: Option[ArrowId] = this match { case id: ArrowId => Some(id); case _ => None }
  def asGroupId: Option[GroupId] = this match { case id: GroupId => Some(id); case _ => None }

sealed trait GroupMemberId extends ElementId derives ReadWriter

case class GroupId(value: String) extends GroupMemberId derives CanEqual:
  override def toString: String = value

case class NodeId(value: String) extends GroupMemberId:
  override def toString: String = value

case class ArrowId(value: String) extends ElementId:
  override def toString: String = value

object GroupId:
  given ReadWriter[GroupId] = stringKeyRW(readwriter[String].bimap[GroupId](_.value, GroupId(_)))

object NodeId:
  given ReadWriter[NodeId] = stringKeyRW(readwriter[String].bimap[NodeId](_.value, NodeId(_)))

  def random(): NodeId = NodeId(randomUUIDSafe().take(8))

object ArrowId:
  given ReadWriter[ArrowId] = stringKeyRW(readwriter[String].bimap[ArrowId](_.value, ArrowId(_)))
