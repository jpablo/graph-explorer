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

  /** The id of each DOT subgraph. `names` has the names of the subgraphs in
    * the order of the source (by `_gvid`).
    *
    * [[fromDot]] gives one id to `clusterB`, `cluster_B` and `B`. Thus the
    * first of these subgraphs gets the id of `fromDot`, and each later one
    * gets `<id>_2`, `<id>_3`, and so on. A later subgraph gets the first of
    * these ids that is free: no subgraph has it, and `fromDot` does not give
    * it to a subgraph.
    *
    * A subgraph that is the first with its `fromDot` id keeps that id. Thus
    * a diagram with no collision keeps the ids that it had before this rule,
    * and the metadata that names `group:<id>` still finds its group.
    */
  def fromDotNames(names: Seq[String]): Vector[GroupId] =
    val ids      = names.toVector.map(fromDot(_)._1)
    val reserved = ids.toSet
    ids
      .foldLeft((Vector.empty[GroupId], Set.empty[GroupId])):
        case ((done, used), id) =>
          val free =
            if !used.contains(id) then id
            else Iterator.from(2).map(n => GroupId(s"${id.value}_$n")).filterNot(c => reserved(c) || used(c)).next()
          (done :+ free, used + free)
      ._1

  /** True when [[fromDotNames]] can give `id` to a subgraph with this name:
    * the id of [[fromDot]], or that id with `_2`, `_3`, and so on.
    */
  def canBeFromDot(name: String, id: GroupId): Boolean =
    val base   = fromDot(name)._1.value
    val suffix = id.value.stripPrefix(s"${base}_")
    id.value == base ||
    (id.value.startsWith(s"${base}_") && suffix.toIntOption.exists(n => n >= 2 && n.toString == suffix))

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
