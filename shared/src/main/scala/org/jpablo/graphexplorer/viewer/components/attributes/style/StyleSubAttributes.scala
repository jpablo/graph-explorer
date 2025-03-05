package org.jpablo.graphexplorer.viewer.components.attributes.style

import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.*
import org.jpablo.graphexplorer.viewer.models.AttrStatus.*
import org.jpablo.graphexplorer.viewer.models.{
  AttrStatus,
  AttributeId,
  Attributes,
  AttributesUpdates,
  SelectionAttrValue
}

case class StyleSubAttributes(
    fill:      AttrStatus[Boolean],
    bold:      AttrStatus[Boolean],
    invisible: AttrStatus[Boolean],
    border:    AttrStatus[BorderStyle],
    corner:    AttrStatus[CornerStyle]
) derives CanEqual:

  def ++(other: StyleSubAttributes): StyleSubAttributes =
    StyleSubAttributes(
      fill      = other.fill.orElse(fill),
      bold      = other.bold.orElse(bold),
      invisible = other.invisible.orElse(invisible),
      border    = other.border.orElse(border),
      corner    = other.corner.orElse(corner)
    )

  private def toKV[A](status: AttrStatus[A], attrId: AttributeId, default: A)(using
      CanEqual[A, A]
  ): Option[(AttributeId, AttrValue)] =
    status match
      case Single(b) if b != default => Some(attrId -> AttrValue(b.toString))
      case _                         => None

  def toSyntheticAttributes: Attributes =
    Attributes(
      Seq(
        toKV(fill, FillStyle.attrId, FillStyle.default),
        toKV(bold, BoldStyle.attrId, BoldStyle.default),
        toKV(invisible, InvisibleStyle.attrId, InvisibleStyle.default),
        toKV(border, BorderStyle.attrId, BorderStyle.default),
        toKV(corner, CornerStyle.attrId, CornerStyle.default)
      ).flatten.toMap
    )

  def toDotString: String =
    val fillPart = if fill.is(true) then List(NodeStyle.filled.toString) else Nil
    val boldPart = if bold.is(true) then List(NodeStyle.bold.toString) else Nil
    val invisPart = if invisible.is(true) then List(NodeStyle.invis.toString) else Nil

    val borderPart = border match
      case Single(b) if b != BorderStyle.default => List(b.toString)
      case _                                     => Nil
    val shapeModPart =
      corner match
        case Single(c) if c != CornerStyle.default => List(c.toString)
        case _                                     => Nil
    (fillPart ++ boldPart ++ invisPart ++ shapeModPart ++ borderPart).mkString(",")

  def isMissing: Boolean =
    this == StyleSubAttributes.missing

object StyleSubAttributes:
  val missing = StyleSubAttributes(
    fill      = Missing,
    bold      = Missing,
    invisible = Missing,
    border    = Missing,
    corner    = Missing
  )

  val multiple = StyleSubAttributes(
    fill      = Multiple,
    bold      = Multiple,
    invisible = Multiple,
    border    = Multiple,
    corner    = Multiple
  )

  def from(attrs: Attributes): Option[StyleSubAttributes] =
    attrs.get(NodeStyle.attrId).map(StyleSubAttributes.parse)

  def from(attrs: AttributesUpdates): StyleSubAttributes =
    attrs.attrs.get(NodeStyle.attrId).fold(missing)(StyleSubAttributes.from)

  def from(attrValue: SelectionAttrValue): StyleSubAttributes =
    attrValue match
      case Single(value) => StyleSubAttributes.parse(value)
      case Multiple      => multiple
      case Missing       => missing

  def fromSynthetic(attrs: Attributes): StyleSubAttributes =
    val borderStr = attrs.get(BorderStyle.attrId).map(_.toString)
    val border = BorderStyle.values.find(style => borderStr.contains(style.toString))

    val cornerStr = attrs.get(CornerStyle.attrId).map(_.toString)
    val corner = CornerStyle.values.find(style => cornerStr.contains(style.toString))

    StyleSubAttributes(
      fill      = Single(attrs.get(FillStyle.attrId).exists(_.isTrue)),
      bold      = Single(attrs.get(BoldStyle.attrId).exists(_.isTrue)),
      invisible = Single(attrs.get(InvisibleStyle.attrId).exists(_.isTrue)),
      border    = Single(border.getOrElse(BorderStyle.default)),
      corner    = Single(corner.getOrElse(CornerStyle.default))
    )

  // Examples:
  // [style="a,b"] --> a:true,b:true
  // [style="a"] --> a:true,b:false
  // [style="b"] --> a:false,b:true
  // [style=""] --> a:false,b:false
  // [] --> a:missing,b:missing
  def parse(attrValue: AttrValue): StyleSubAttributes =
    val parts = attrValue.toString.split(",").map(_.trim).filterNot(_.isEmpty).toSet
    if parts.isEmpty then
      StyleSubAttributes.missing
    else
      StyleSubAttributes(
        fill      = Single(NodeStyle.filled.toString in parts),
        bold      = Single(NodeStyle.bold.toString in parts),
        invisible = Single(NodeStyle.invis.toString in parts),
        border    = Single(BorderStyle.values.find(_.toString in parts).getOrElse(BorderStyle.default)),
        corner    = Single(CornerStyle.values.find(_.toString in parts).getOrElse(CornerStyle.default))
      )
