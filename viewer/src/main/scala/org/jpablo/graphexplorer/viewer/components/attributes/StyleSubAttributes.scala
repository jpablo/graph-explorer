package org.jpablo.graphexplorer.viewer.components.attributes

import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.*
import org.jpablo.graphexplorer.viewer.extensions.extraAttributes.*
import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue

case class StyleSubAttributes(
    fill:     Boolean = false,
    bold:     Boolean = false,
    border:   Option[BorderStyle] = None,
    shapeMod: Option[ShapeModStyle] = None,
):
  def toDotString: String =
    val fillPart = if fill then List(NodeStyle.filled) else Nil
    val boldPart = if bold then List(NodeStyle.bold.toString) else Nil
    val shapeModPart =
      shapeMod.flatMap:
        case ShapeModStyle.none => None
        case s                  => Some(s.toString)
    val borderPart = border.map(_.toString).toList
    (fillPart ++ boldPart ++ shapeModPart.toSeq ++ borderPart).mkString(",")

  def ++(other: StyleSubAttributes): StyleSubAttributes =
    StyleSubAttributes(
      fill     = other.fill || fill,
      bold     = other.bold || bold,
      border   = other.border.orElse(border),
      shapeMod = other.shapeMod.orElse(shapeMod)
    )

object StyleSubAttributes:
  val empty = StyleSubAttributes()

  def from(attrValue: Option[AttrValue]): StyleSubAttributes =
    attrValue match
      case None => empty
      case Some(attrValue) =>
        val parts = attrValue.toString.split(",").map(_.trim).filterNot(_.isEmpty).toSet
        StyleSubAttributes(
          fill     = NodeStyle.filled in parts,
          bold     = NodeStyle.bold.toString in parts,
          border   = BorderStyle.values.find(_.toString in parts),
          shapeMod = ShapeModStyle.values.find(_.toString in parts)
        )
