package org.jpablo.graphexplorer.viewer.models

import org.jpablo.graphexplorer.viewer.formats.dot.ast.{Attr, AttrValue}
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.DotAttribute
import upickle.default.*

import scala.annotation.targetName
import scala.collection.immutable.VectorMap
import scala.language.implicitConversions

// -------------------
// --- AttrStatus ---
// -------------------

enum AttrStatus[+A] derives CanEqual:
  case Single(value: A)
  case Multiple
  case Missing

  override def toString: String =
    this match
      case Single(v) => v.toString
      case Multiple  => "Multiple"
      case Missing   => "Missing"

  def toOption: Option[A] =
    this match
      case Single(v) => Some(v)
      case _         => None

  def map[B](f: A => B): AttrStatus[B] =
    this match
      case Single(v) => Single(f(v))
      case Multiple  => Multiple
      case Missing   => Missing

  def getOrElse[A2 >: A](default: A2): A2 =
    this match
      case Single(v) => v
      case _         => default

  def orElse[A2 >: A](other: AttrStatus[A2]): AttrStatus[A2] =
    this match
      case s @ Single(_) => s
      case _             => other

  def exists[A2 >: A](p: A2 => Boolean): Boolean =
    this match
      case Single(v) => p(v)
      case _         => false

  def forall[A2 >: A](p: A2 => Boolean): Boolean =
    this match
      case Single(v) => p(v)
      case _         => true

  def is[A2 >: A](a: A2)(using CanEqual[A2, A]): Boolean =
    exists(a == _)

type AttrValueWithStatus = AttrStatus[AttrValue]

case class AttributeId(value: String) extends AnyVal:
  override def toString: String = value

object AttributeId:
  given ReadWriter[AttributeId] = stringKeyRW(readwriter[String].bimap(_.value, AttributeId(_)))

// -------------------
// --- Attributes ---
// -------------------

case class Attributes(values: Map[AttributeId, AttrValue]) extends AnyVal:
  def ++(other: Attributes): Attributes = Attributes(values ++ other.values)
  @targetName("concatValues")
  def ++(other: Map[AttributeId, AttrValue]): Attributes = Attributes(values ++ other)
  def --(other: Set[AttributeId]): Attributes            = Attributes(values -- other)
  @targetName("removeAttributes")
  def --(other: Set[DotAttribute[?]]): Attributes = Attributes(values -- other.map(_.attrId))

  def -(key:  AttributeId): Attributes              = Attributes(values - key)
  def -(attr: DotAttribute[?]): Attributes          = Attributes(values - attr.attrId)
  def +(kv:   (AttributeId, AttrValue)): Attributes = Attributes(values + kv)
  def +(kv:   AttributePair): Attributes            = Attributes(values + kv.toTuple)

  export values.isEmpty

  def contains(key: AttributeId): Boolean =
    values.contains(key)

  def toDotAttr: List[Attr] =
    values.map((k, v) => Attr(k.value, v)).toList

  def get(key: AttributeId): Option[AttrValue] = values.get(key)

  def get(attr: DotAttribute[?]): Option[AttrValue] = values.get(attr.attrId)

  def contains(attrId: AttributeId, attrValue: AttrValue): Boolean =
    get(attrId).contains(attrValue)

  def filterKeys(p: AttributeId => Boolean): Attributes =
    Attributes(values.view.filterKeys(p).toMap)

  def filter(p: (AttributeId, AttrValue) => Boolean): Attributes =
    Attributes(values.filter(p.tupled))

  def filterNot(p: (AttributeId, AttrValue) => Boolean): Attributes =
    Attributes(values.filterNot(p.tupled))

  def getAs[A, B <: DotAttribute[A]](b: B): A =
    get(b.attrId)
      .flatMap(v => b.fromString(v.toString))
      .getOrElse(b.default)

  def toUpdates: AttributeUpdates =
    AttributeUpdates(values.transform((_, v) => AttrStatus.Single(v)))

object Attributes:

  given ReadWriter[Attributes] =
    stringKeyRW(readwriter[Map[AttributeId, AttrValue]].bimap(_.values, Attributes(_)))

  val empty = Attributes(Map.empty)

  /** Order-preserving factory: keeps the pairs' insertion order via VectorMap. Use instead of `of` (which goes through an unordered Map)
    * whenever attribute order matters, e.g. for serialization.
    */
  def fromOrdered(pairs: IterableOnce[(AttributeId, AttrValue)]): Attributes =
    Attributes(VectorMap.from(pairs))

  def of(values: AttributePair*) =
    Attributes(values.map(_.toTuple).toMap)

  @targetName("ofTuple")
  def of(attrs: (String, String)*) =
    Attributes(attrs.map((k, v) => AttributeId(k) -> AttrValue(v)).toMap)

// ------------------------
// --- AttributesUpdates --
// ------------------------

/** A variant of Attributes where the values can be missing for two reasons: missing or multiple.
  *   - Missing means that the attributeId should be removed from the element's attributes.
  *   - Multiple means that the attributeId is present with multiple values in the selection. This distinction is used in the UI.
  *
  * This class is used in two ways:
  *   - As a read model: to send the current selection's attribute values to the UI.
  *
  * Three possible states: AttrStatus = Single, Multiple, Missing
  *
  *   - as a write model: to apply instructions to the attributes of selected elements.
  *
  * Two possible actions: Remove and Set. We reuse Missing and Single for this.
  *
  * @param statuses
  *   When used as a write model, this map contains *instructions* to update the attributes of selected elements.
  */
case class AttributeUpdates(statuses: Map[AttributeId, AttrValueWithStatus] = Map.empty):
  def applyTo(attrs: Attributes): Attributes =
    Attributes(
      statuses.foldLeft(attrs.values):
        case (attrs, (attrId, status)) =>
          status match
            case AttrStatus.Single(v) => attrs + (attrId -> v)
            case AttrStatus.Multiple  => attrs
            case AttrStatus.Missing   => attrs - attrId
    )

  def -(key: AttributeId) = AttributeUpdates(statuses - key)

  def +(kv: (attrId: AttributeId, value: AttrValue)) = AttributeUpdates(statuses + (kv.attrId -> AttrStatus.Single(kv.value)))

  @targetName("concatUpdates")
  def +(kv: (AttributeId, AttrValueWithStatus)) = AttributeUpdates(statuses + kv)

object AttributeUpdates:

  def of(values: AttributePair*) =
    AttributeUpdates(
      values
        .map: pair =>
          val (attrId, attrValue) = pair.toTuple
          attrId -> AttrStatus.Single(attrValue)
        .toMap
    )

  def remove(keys: Set[AttributeId]): AttributeUpdates =
    AttributeUpdates(keys.map(_ -> AttrStatus.Missing).toMap)

// ------------------------
// --- AttributePair --
// ------------------------

trait AttributePair:
  def toTuple: (AttributeId, AttrValue)

object AttributePair:
  implicit def pair[A](p: (DotAttribute[A], A)): AttributePair =
    new AttributePair:
      def toTuple: (AttributeId, AttrValue) = p._1.attrId -> AttrValue(p._2.toString)
