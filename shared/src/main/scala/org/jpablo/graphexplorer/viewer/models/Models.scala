package org.jpablo.graphexplorer.viewer.models

import org.jpablo.graphexplorer.viewer.extensions.notIn
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.models.Arrow.titleIdSeparator
import org.jpablo.graphexplorer.viewer.models.Attributable.idAttributeKey
import org.jpablo.graphexplorer.viewer.utils.Utils
import org.jpablo.graphexplorer.viewer.utils.Utils.randomUUIDSafe
import upickle.default.*

import scala.annotation.targetName
import scala.compiletime.asMatchable

sealed trait ElementId derives CanEqual, ReadWriter:
  def value: String

  def isGroupId: Boolean = this match { case _: GroupId => true; case _ => false }
  def isNodeId: Boolean = this match { case _: NodeId => true; case _ => false }
  def isArrowId: Boolean = this match { case _: ArrowId => true; case _ => false }

case class GroupId(value: String) extends ElementId derives CanEqual:
  override def toString: String = value

object GroupId:
  given rw: ReadWriter[GroupId] = stringKeyRW(readwriter[String].bimap[GroupId](_.value, GroupId(_)))


case class NodeId(value: String) extends ElementId:
  override def toString: String = value

case class ArrowId(value: String) extends ElementId:
  override def toString: String = value

object ArrowId:

  given rw: ReadWriter[ArrowId] = stringKeyRW(readwriter[String].bimap[ArrowId](_.value, ArrowId(_)))

  def isArrowId(id: ElementId): Boolean =
    id.value.contains(Arrow.titleIdSeparator)


case class IdsByKind(
  clusters: Set[GroupId] = Set.empty,
  nodes   : Set[NodeId] = Set.empty,
  arrows  : Set[ArrowId] = Set.empty
)


case class ElementIds(ids: Set[? <: ElementId] = Set.empty) extends AnyVal:

  def upcast = ids.asInstanceOf[Set[ElementId]]

  def isEmpty: Boolean = ids.isEmpty
  def nonEmpty: Boolean = ids.nonEmpty
  def size: Int = ids.size
  def head: ElementId = ids.head
  infix def intersect(that: ElementIds): ElementIds = ElementIds(upcast intersect that.upcast)
  def toggle(id: ElementId) = if id notIn this then this + id else this - id

  def contains(id: ElementId): Boolean =
    upcast.contains(id)

  def filter(p: ElementId => Boolean): ElementIds =
    ElementIds(ids.filter(p))

  def + (that: ElementId): ElementIds = ElementIds(upcast + that)
  def - (that: ElementId): ElementIds = ElementIds(upcast - that)
  def ++ (that: ElementIds): ElementIds = ElementIds(upcast ++ that.upcast)
  def -- (that: ElementIds): ElementIds = ElementIds(upcast -- that.upcast)

  def nodeIds = ids.collect { case id: NodeId => id }
  def arrowIds = ids.collect { case id: ArrowId => id }
  def groupIds = ids.collect { case id: GroupId => id }

  def classify: IdsByKind =
    ids.foldLeft(IdsByKind()): (acc, eId) =>
      eId match
        case id: GroupId => acc.copy(clusters = acc.clusters + id)
        case id: NodeId => acc.copy(nodes = acc.nodes + id)
        case id: ArrowId => acc.copy(arrows = acc.arrows + id)

object ElementIds:
  def from(ids: ElementId*): ElementIds = ElementIds(ids.toSet)

  given rw: ReadWriter[ElementIds] = readwriter[Set[ElementId]].bimap[ElementIds](_.upcast, ElementIds(_))


object NodeId:
  given rw: ReadWriter[NodeId] = stringKeyRW(readwriter[String].bimap[NodeId](_.value, NodeId(_)))

  def random(): NodeId = NodeId(randomUUIDSafe().take(8))

  def isArrowId(nodeId: ElementId): Boolean =
    nodeId.value.contains(Arrow.titleIdSeparator)

  def isClusterId(nodeId: ElementId): Boolean =
    nodeId.value.startsWith("cluster_")



type ViewerKind = Option[String]

trait Attributable:
  def attributes: Attributes

  def label: AttrValue =
    attributes.values.getOrElse(AttributeId("label"), AttrValue.empty)

  def idAttr: AttrValue =
    attributes.values.getOrElse(idAttributeKey, AttrValue.empty)

object Attributable:
  val idAttributeKey = AttributeId("id")

case class ViewerNode(
    id        :    NodeId,
    attributes: Attributes = Attributes.empty,
    kind      :  ViewerKind = None
) extends Attributable

object ViewerNode:
  def node(name: String, attrs: Map[AttributeId, AttrValue] = Map.empty) =
    ViewerNode(NodeId(name), Attributes(attrs))

// ---- Edges ------

case class Arrow(
    source    : NodeId,
    target    : NodeId,
    attributes:  Attributes = Attributes.empty,
    seq       :    Int = 0
) extends Attributable:

  // Re-create the string used by graphviz in the `<title>` element of the SVG.
  val id = ArrowId(s"${source.value}$titleIdSeparator${target.value}:$seq")

  def nodeIds = Set(source, target)
  def endpoints = Set(source, target)

  def mergeAttrs(other: Attributes): Arrow = copy(attributes = attributes ++ other)

end Arrow

object Arrow:

  val titleIdSeparator = "->"

  def arrow(t: (String, String), attrs: Map[AttributeId, AttrValue] = Map.empty, seq: Int = 0): Arrow =
    new Arrow(NodeId(t._1), NodeId(t._2), Attributes(attrs), seq)

  // example:
  // <title>A->B</title>
  val edgeTitlePattern = raw"(.+)$titleIdSeparator(.+)".r

  // Expects `title` to be the "<title>" generated by graphviz for arrows.
  // `idAttr` is used to disambiguate multiple arrows between the same nodes.
  def fromGraphvizTitle(title: String, idAttr: String): Option[Arrow] =
    title match
      case edgeTitlePattern(l, r) if l.trim.nonEmpty && r.trim.nonEmpty =>
        Some(arrow(l.trim -> r.trim, seq = idAttr.toInt))

      case _ => None

  given arrowOrd: scala.Ordering[Arrow]:
    def compare(x: Arrow, y: Arrow): Int =
      val s = x.source.value `compareTo` y.source.value
      if s != 0 then s
      else
        val t = x.target.value `compareTo` y.target.value
        if t != 0 then t else x.idAttr.toString `compareTo` y.idAttr.toString
end Arrow

enum AttrStatus[+A] derives CanEqual:
  case Single(value: A)
  case Multiple
  case Missing

  override def toString: String =
    this match
      case Single(v) => v.toString
      case Multiple => "Multiple"
      case Missing => "Missing"

  def map[B](f: A => B): AttrStatus[B] =
    this match
      case Single(v) => Single(f(v))
      case Multiple => Multiple
      case Missing => Missing

  def getOrElse[A2 >: A](default: A2): A2 =
    this match
      case Single(v) => v
      case _ => default

  def orElse[A2 >: A](other: AttrStatus[A2]): AttrStatus[A2] =
    this match
      case s @ Single(_) => s
      case _ => other

  def exists[A2 >: A](p: A2 => Boolean): Boolean =
    this match
      case Single(v) => p(v)
      case _ => false

  def is[A2 >: A](a: A2)(using CanEqual[A2, A]): Boolean =
    exists(a == _)


type SelectionAttrValue = AttrStatus[AttrValue]

case class AttributeId(value: String) extends AnyVal:
  override def toString: String = value

case class Attributes(values: Map[AttributeId, AttrValue]) extends AnyVal:
  def ++(other: Attributes): Attributes = Attributes(values ++ other.values)
  @targetName("concatValues")
  def ++(other: Map[AttributeId, AttrValue]): Attributes = Attributes(values ++ other)
  def --(other: Set[AttributeId]): Attributes = Attributes(values -- other)
  def -(key: AttributeId): Attributes = Attributes(values - key)
  def +(kv: (AttributeId, AttrValue)): Attributes = Attributes(values + kv)
  def get(key: AttributeId): Option[AttrValue] = values.get(key)

  def toUpdates: AttributesUpdates =
    AttributesUpdates(values.transform((_, v) => AttrStatus.Single(v)))


case class AttributesUpdates(
  attrs: Map[AttributeId, SelectionAttrValue],
  update: Map[AttributeId, AttrValue] = Map.empty,
  remove: Set[AttributeId] = Set.empty
):
  def -(key: AttributeId): AttributesUpdates = copy(remove = remove + key)
  def +(kv: (AttributeId, AttrValue)): AttributesUpdates = copy(update = update + kv)

  def singleAttributes: Attributes =
    Attributes(attrs.collect { case (k, AttrStatus.Single(v)) => k -> v } )

  def applyUpdates: Attributes =
    applyUpdatesTo(singleAttributes)

  def applyUpdatesTo(attrs: Attributes): Attributes =
    attrs ++ update -- remove

object Attributes:
  val empty = Attributes(Map.empty)

// ---- groups ------

case class ViewerGroup(
    id        : GroupId,
    attributes: Attributes = Attributes.empty,
    edgeAttrs : Attributes = Attributes.empty,
    nodeAttrs : Attributes = Attributes.empty
) extends Attributable

object ViewerGroup:
  def empty(nodeId: GroupId) = ViewerGroup(nodeId)
end ViewerGroup
