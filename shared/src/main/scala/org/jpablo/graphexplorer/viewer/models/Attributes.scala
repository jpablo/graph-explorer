package org.jpablo.graphexplorer.viewer.models

import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{DotAttribute, DotAttributeEnum}
import upickle.default.*

import scala.annotation.targetName

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

case class Attributes(values: Map[AttributeId, AttrValue]) extends AnyVal:
  def ++(other: Attributes): Attributes = Attributes(values ++ other.values)
  @targetName("concatValues")
  def ++(other: Map[AttributeId, AttrValue]): Attributes = Attributes(values ++ other)
  def --(other: Set[AttributeId]): Attributes = Attributes(values -- other)

  def -(key: AttributeId): Attributes = Attributes(values - key)
  def +(kv:  (AttributeId, AttrValue)): Attributes = Attributes(values + kv)

  def get(key: AttributeId): Option[AttrValue] = values.get(key)

  def getAs[A, B <: DotAttribute[A]](b: B): A =
    get(b.attrId)
      .flatMap(v => b.fromString(v.toString))
      .getOrElse(b.default)

  def toUpdates: AttributesUpdates =
    AttributesUpdates(values.transform((_, v) => AttrStatus.Single(v)))

case class AttributesUpdates(
    existing: Map[AttributeId, SelectionAttrValue] = Map.empty,
    update:   Map[AttributeId, AttrValue] = Map.empty,
    remove:   Set[AttributeId] = Set.empty
):
  def -(key: AttributeId): AttributesUpdates = copy(remove = remove + key)
  def +(kv:  (AttributeId, AttrValue)): AttributesUpdates = copy(update = update + kv)

  def applyUpdatesTo(attrs: Attributes): Attributes =
    attrs ++ update -- remove

object Attributes:
  val empty = Attributes(Map.empty)

  def of(values: AttributePair*) =
    Attributes(values.map(_.toTuple).toMap)



trait AttributePair:
  def toTuple: (AttributeId, AttrValue)


object AttributePair:
  implicit def pair[A](p: (DotAttributeEnum[A], A)): AttributePair =
    new AttributePair:
      def toTuple: (AttributeId, AttrValue) = p._1.attrId -> AttrValue(p._2.toString)
