package org.jpablo.graphexplorer.viewer.components.attributes.styleSubAttributes

import org.jpablo.graphexplorer.viewer.components.attributes.styleSubAttributes.StyleSubAttributes.{default, missing}
import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{BoldStyle, BorderStyle, CornerStyle, FillStyle, InvisibleStyle, NodeStyle}
import org.jpablo.graphexplorer.viewer.models.AttrStatus.*
import org.jpablo.graphexplorer.viewer.models.{AttrStatus, AttributeId, Attributes, AttributesUpdates, SelectionAttrValue}

case class StyleSubAttributes(
    fill:      AttrStatus[Boolean],
    bold:      AttrStatus[Boolean],
    invisible: AttrStatus[Boolean],
    border:    AttrStatus[BorderStyle],
    corner:    AttrStatus[CornerStyle]
) derives CanEqual:

  def withDefaults =
    StyleSubAttributes(
      fill      = fill.orElse(default.fill),
      bold      = bold.orElse(default.bold),
      invisible = invisible.orElse(default.invisible),
      border    = border.orElse(default.border),
      corner    = corner.orElse(default.corner)
    )

  def ++(self: StyleSubAttributes): StyleSubAttributes =
    StyleSubAttributes(
      fill      = self.fill.orElse(fill),
      bold      = self.bold.orElse(bold),
      invisible = self.invisible.orElse(invisible),
      border    = self.border.orElse(border),
      corner    = self.corner.orElse(corner)
    )

  private def toKV[A](status: AttrStatus[A], attrId: AttributeId)(using
      CanEqual[A, A]
  ): Option[(AttributeId, AttrValue)] =
    status match
      case Single(b) => Some(attrId -> AttrValue(b.toString))
      case _         => None

  // default values will be omitted in the dot string
  def toSubAttributes: Attributes =
    Attributes(
      Seq(
        toKV(fill, FillStyle.attrId),
        toKV(bold, BoldStyle.attrId),
        toKV(invisible, InvisibleStyle.attrId),
        toKV(border, BorderStyle.attrId),
        toKV(corner, CornerStyle.attrId)
      ).flatten.toMap
    )

  // The "simple" mode is used when the style is not a combination of multiple styles
  def toStyleStringSimple: String =
    val fillPart = if fill.is(true) then List(NodeStyle.filled.toString) else Nil
    val boldPart = if bold.is(true) then List(NodeStyle.bold.toString) else Nil
    val invisPart = if invisible.is(true) then List(NodeStyle.invis.toString) else Nil
    val borderPart = border match
      case Single(b) if b != BorderStyle.default => List(b.toString)
      case _                                     => Nil
    val cornerPart =
      corner match
        case Single(c) if c != CornerStyle.default => List(c.toString)
        case _                                     => Nil
    (fillPart ++ boldPart ++ invisPart ++ cornerPart ++ borderPart).mkString(",")

  // This method is used to combine the global style with the local style
  // Rules: (local = this)
  // rule 1: if local == global then remove local style
  // rule 2: (if local != global and) global not default and local is default then local is style=“”
  // rule 3: else combined with global and use simple mode
  def toStyleCombined(global: StyleSubAttributes): Option[String] =
    if this == global then
      None
    else if global != default && this == missing then
      None
    else if global != default && this == default then
      Some("")
    else
      // TODO: analyze this further
//      val gd = global.withDefaults
//      val dd = this.withDefaults
//      val merged = gd ++ dd
//      val simple = merged.toStyleStringSimple
      val mergedNoDefaults = global ++ this
      val simpleNoDefaults = mergedNoDefaults.toStyleStringSimple
      if mergedNoDefaults == global then
        None
      else
        Some(simpleNoDefaults)

  def isMissing: Boolean =
    this == StyleSubAttributes.missing

object StyleSubAttributes:
  val subAttributeIds =
    Set(FillStyle.attrId, BoldStyle.attrId, InvisibleStyle.attrId, BorderStyle.attrId, CornerStyle.attrId)

  val default = StyleSubAttributes(
    fill      = Single(FillStyle.default),
    bold      = Single(BoldStyle.default),
    invisible = Single(InvisibleStyle.default),
    border    = Single(BorderStyle.default),
    corner    = Single(CornerStyle.default)
  )

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
    attrs.existing.get(NodeStyle.attrId).fold(missing)(StyleSubAttributes.from)

  def from(attrValue: SelectionAttrValue): StyleSubAttributes =
    attrValue match
      case Single(value) => StyleSubAttributes.parse(value)
      case Multiple      => multiple
      case Missing       => missing

  // TODO: we need to use Missing!!! (at least for local values, not sure about global)
  def fromSubAttributes(attrs: Attributes): StyleSubAttributes =
    val borderStr = attrs.get(BorderStyle.attrId).map(_.toString)
    val border = BorderStyle.values.find(style => borderStr.contains(style.toString))

    val cornerStr = attrs.get(CornerStyle.attrId).map(_.toString)
    val corner = CornerStyle.values.find(style => cornerStr.contains(style.toString))

    StyleSubAttributes(
      fill      = attrs.get(FillStyle.attrId).map(_.isTrue).fold(Missing)(Single(_)),
      bold      = attrs.get(BoldStyle.attrId).map(_.isTrue).fold(Missing)(Single(_)),
      invisible = attrs.get(InvisibleStyle.attrId).map(_.isTrue).fold(Missing)(Single(_)),
      border    = border.map(Single(_)).getOrElse(Missing),
      corner    = corner.map(Single(_)).getOrElse(Missing)
    )

  // Examples:
  // [style="a,b"] --> a:true,b:true
  // [style="a"] --> a:true,b:false
  // [style="b"] --> a:false,b:true
  // [style=""] --> a:false,b:false
  // [] --> a:missing,b:missing == a:false,b:false

  val singleTrue = Single(true)
  val singleFalse = Single(false)
  private inline def singleBoolean(value: Boolean): AttrStatus[Boolean] =
    if value then singleTrue else singleFalse

  def parse(attrValue: AttrValue): StyleSubAttributes =
    val parts = attrValue.toString.split(",").map(_.trim).filterNot(_.isEmpty).toSet
    if parts.isEmpty then
      missing
    else
      StyleSubAttributes(
        fill      = singleBoolean(NodeStyle.filled.toString in parts),
        bold      = singleBoolean(NodeStyle.bold.toString in parts),
        invisible = singleBoolean(NodeStyle.invis.toString in parts),
        border    = Single(BorderStyle.values.find(_.toString in parts).getOrElse(BorderStyle.default)),
        corner    = Single(CornerStyle.values.find(_.toString in parts).getOrElse(CornerStyle.default))
      )
