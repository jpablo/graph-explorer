package org.jpablo.graphexplorer.viewer.extensions.extraAttributes

import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.{DotAttributeEnum, DotAttributeSimple}

object FillStyle extends DotAttributeSimple[Boolean]:
  val default = false
  val label = "Filled"
  val filled = "filled"

object BoldStyle extends DotAttributeSimple[Boolean]:
  val default = false
  val label = "Bold"
  val bold = "bold"

object InvisibleStyle extends DotAttributeSimple[Boolean]:
  val default = false
  val label = "Invisible"
  val invis = "invis"

enum BorderStyle:
  case solid, dashed, dotted

object BorderStyle extends DotAttributeEnum[BorderStyle]:
  val default = solid
  val label = "Border Style"

  override def valuesWithLabel = Array(
    ("Solid", solid),
    ("Dashed", dashed),
    ("Dotted", dotted)
  )

enum CornerStyle:
  case rounded, diagonals, normal

object CornerStyle extends DotAttributeEnum[CornerStyle]:
  val default = normal
  val label = "Corner Style"

  override def valuesWithLabel = Array(
    ("Rounded", rounded),
    ("Diagonals", diagonals),
    ("Normal", normal)
  )
