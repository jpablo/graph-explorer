package org.jpablo.graphexplorer.viewer.components.attributes

import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.*
import org.jpablo.graphexplorer.viewer.extensions.extraAttributes.*
import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue

case class StyleSubAttributes(
    fill:      Boolean = false,
    bold:      Boolean = false,
    invisible: Boolean = false,
    border:    BorderStyle = BorderStyle.default,
    shapeMod:  CornerStyle = CornerStyle.default
):
  def toDotString: String =
    val fillPart = if fill then List(NodeStyle.filled) else Nil
    val boldPart = if bold then List(NodeStyle.bold.toString) else Nil
    val invisPart = if invisible then List(NodeStyle.invis.toString) else Nil
    val borderPart =
      border match
        case BorderStyle.default => Nil
        case s                   => List(s.toString)
    val shapeModPart =
      shapeMod match
        case CornerStyle.default => Nil
        case s                   => List(s.toString)
    (fillPart ++ boldPart ++ invisPart ++ shapeModPart ++ borderPart).mkString(",")

  // this: defaults
  // other: local
  def ++(local: StyleSubAttributes): StyleSubAttributes =
    StyleSubAttributes(
      fill      = local.fill,
      bold      = local.bold,
      invisible = local.invisible,
      border    = local.border,
      shapeMod  = local.shapeMod
    )

  def isEmpty: Boolean =
    this == StyleSubAttributes.empty

object StyleSubAttributes:
  val empty = StyleSubAttributes()

  def from(attrValue: AttrValue): StyleSubAttributes =
    val parts = attrValue.toString.split(",").map(_.trim).filterNot(_.isEmpty).toSet
    StyleSubAttributes(
      fill      = NodeStyle.filled in parts,
      bold      = NodeStyle.bold.toString in parts,
      invisible = NodeStyle.invis.toString in parts,
      border    = BorderStyle.values.find(_.toString in parts).getOrElse(BorderStyle.default),
      shapeMod  = CornerStyle.values.find(_.toString in parts).getOrElse(CornerStyle.default)
    )
