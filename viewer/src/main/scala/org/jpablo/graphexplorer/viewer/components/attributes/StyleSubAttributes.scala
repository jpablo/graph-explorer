package org.jpablo.graphexplorer.viewer.components.attributes

import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.*
import org.jpablo.graphexplorer.viewer.extensions.extraAttributes.*
import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.models.{AttrStatus, Attributes, AttributesUpdates, SelectionAttrValue}
import org.jpablo.graphexplorer.viewer.models.AttrStatus.*

case class StyleSubAttributes(
    fill:      AttrStatus[Boolean],
    bold:      AttrStatus[Boolean],
    invisible: AttrStatus[Boolean],
    border:    AttrStatus[BorderStyle],
    corner:    AttrStatus[CornerStyle]
):

  def ++ (other: StyleSubAttributes): StyleSubAttributes =
    StyleSubAttributes(
      fill      = other.fill.orElse(fill),
      bold      = other.bold.orElse(bold),
      invisible = other.invisible.orElse(invisible),
      border    = other.border.orElse(border),
      corner    = other.corner.orElse(corner)
    )

  def toDotString: String =
    val fillPart = if fill.is(true) then List(NodeStyle.filled) else Nil
    val boldPart = if bold.is(true) then List(NodeStyle.bold.toString) else Nil
    val invisPart = if invisible.is(true) then List(NodeStyle.invis.toString) else Nil

    val borderPart = border match
      case AttrStatus.Single(b) if b != BorderStyle.default => List(b.toString)
      case _                                            => Nil
    val shapeModPart =
      corner match
        case AttrStatus.Single(c) if c != CornerStyle.default => List(c.toString)
        case _                                            => Nil
    (fillPart ++ boldPart ++ invisPart ++ shapeModPart ++ borderPart).mkString(",")

  def isMissing: Boolean =
    this == StyleSubAttributes.missing

object StyleSubAttributes:
  val missing = StyleSubAttributes(
    fill      = AttrStatus.Missing,
    bold      = AttrStatus.Missing,
    invisible = AttrStatus.Missing,
    border    = AttrStatus.Missing,
    corner    = AttrStatus.Missing
  )

  val multiple = StyleSubAttributes(
    fill      = AttrStatus.Multiple,
    bold      = AttrStatus.Multiple,
    invisible = AttrStatus.Multiple,
    border    = AttrStatus.Multiple,
    corner    = AttrStatus.Multiple
  )

  def from(attrs: Attributes): Option[StyleSubAttributes] =
    attrs.get(NodeStyle.attrId).map(StyleSubAttributes.from)

  def from(attrs: AttributesUpdates): StyleSubAttributes =
    attrs.attrs.get(NodeStyle.attrId).fold(missing)(StyleSubAttributes.from)

  def from(attrValue: SelectionAttrValue): StyleSubAttributes =
    attrValue match
      case Single(value) => StyleSubAttributes.from(value)
      case Multiple      => multiple
      case Missing       => missing

  // Examples:
  // [style="a,b"] --> a:true,b:true
  // [style="a"] --> a:true,b:false
  // [style="b"] --> a:false,b:true
  // [style=""] --> a:false,b:false
  // [] --> a:missing,b:missing
  def from(attrValue: AttrValue): StyleSubAttributes =
    val parts = attrValue.toString.split(",").map(_.trim).filterNot(_.isEmpty).toSet
    if parts.isEmpty then
      StyleSubAttributes.missing
    else
      StyleSubAttributes(
        fill      = AttrStatus.Single(NodeStyle.filled in parts),
        bold      = AttrStatus.Single(NodeStyle.bold.toString in parts),
        invisible = AttrStatus.Single(NodeStyle.invis.toString in parts),
        border    = AttrStatus.Single(BorderStyle.values.find(_.toString in parts).getOrElse(BorderStyle.default)),
        corner    = AttrStatus.Single(CornerStyle.values.find(_.toString in parts).getOrElse(CornerStyle.default))
      )
