package org.jpablo.graphexplorer.viewer.models

import org.jpablo.graphexplorer.viewer.formats.dot.ast.{Attr, AttrValue}
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.DotAttribute
import upickle.default.*

import scala.annotation.targetName

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

type SelectionAttrValue = AttrStatus[AttrValue]

case class AttributeId(value: String) extends AnyVal:
  override def toString: String = value

// -------------------
// --- Attributes ---
// -------------------

case class Attributes(values: Map[AttributeId, AttrValue]) extends AnyVal:
  def ++(other: Attributes): Attributes = Attributes(values ++ other.values)
  @targetName("concatValues")
  def ++(other: Map[AttributeId, AttrValue]): Attributes = Attributes(values ++ other)
  def --(other: Set[AttributeId]): Attributes            = Attributes(values -- other)

  def -(key: AttributeId): Attributes              = Attributes(values - key)
  def +(kv:  (AttributeId, AttrValue)): Attributes = Attributes(values + kv)

  def contains(key: AttributeId): Boolean =
    values.contains(key)

  def toDotAttr: List[Attr] =
    values.map((k, v) => Attr(k.value, v)).toList

  def get(key:  AttributeId): Option[AttrValue]     = values.get(key)
  def get(attr: DotAttribute[?]): Option[AttrValue] = values.get(attr.attrId)

  def filterKeys(p: AttributeId => Boolean): Attributes =
    Attributes(values.view.filterKeys(p).toMap)

  def getAs[A, B <: DotAttribute[A]](b: B): A =
    get(b.attrId)
      .flatMap(v => b.fromString(v.toString))
      .getOrElse(b.default)

  def toUpdates: AttributesUpdates =
    AttributesUpdates(values.transform((_, v) => AttrStatus.Single(v)))

object Attributes:
  val empty = Attributes(Map.empty)

  def of(values: AttributePair*) =
    Attributes(values.map(_.toTuple).toMap)

  @targetName("ofTuple")
  def of(attrs: (String, String)*) =
    Attributes(attrs.map((k, v) => AttributeId(k) -> AttrValue(v)).toMap)

// ------------------------
// --- AttributesUpdates --
// ------------------------

case class AttributesUpdates(updates: Map[AttributeId, SelectionAttrValue] = Map.empty):
  def applyUpdates(attrs: Attributes): Attributes =
    Attributes(
      updates.foldLeft(attrs.values):
        case (acc, (attrId, status)) =>
          status match
            case AttrStatus.Single(v) => acc + (attrId -> v)
            case _                    => acc - attrId
    )

  def -(key: AttributeId) = AttributesUpdates(updates - key)

  def +(kv: (AttributeId, AttrValue)) = AttributesUpdates(updates + (kv._1 -> AttrStatus.Single(kv._2)))

  @targetName("concatUpdates")
  def +(kv: (AttributeId, SelectionAttrValue)) = AttributesUpdates(updates + kv)

object AttributesUpdates:

  def of(values: AttributePair*) =
    AttributesUpdates(
      values
        .map: pair =>
          val (attrId, attrValue) = pair.toTuple
          attrId -> AttrStatus.Single(attrValue)
        .toMap
    )

  def remove(keys: Set[AttributeId]): AttributesUpdates =
    AttributesUpdates(keys.map(_ -> AttrStatus.Missing).toMap)

// ------------------------
// --- AttributePair --
// ------------------------

trait AttributePair:
  def toTuple: (AttributeId, AttrValue)

object AttributePair:
  implicit def pair[A](p: (DotAttribute[A], A)): AttributePair =
    new AttributePair:
      def toTuple: (AttributeId, AttrValue) = p._1.attrId -> AttrValue(p._2.toString)
