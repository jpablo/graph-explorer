package org.jpablo.graphexplorer.viewer.formats.dot.attributes

// "Synthetic" attributes:
// These are attributes that are not part of the DOT language,
// but are used to represent a combination of multiple attributes.

/** Part of the "Style" attribute
  */
object FillStyle extends DotAttributeSimple[Boolean]:
  val default = false
  val label = "Filled"
  val filled = "filled"

/** Part of the "Style" attribute
  */
object BoldStyle extends DotAttributeSimple[Boolean]:
  val default = false
  val label = "Bold"
  val bold = "bold"

/** Part of the "Style" attribute
  */
object InvisibleStyle extends DotAttributeSimple[Boolean]:
  val default = false
  val label = "Invisible"
  val invis = "invis"

enum BorderStyle derives CanEqual:
  case solid, dashed, dotted

/** Part of the "Style" attribute
  */
object BorderStyle extends DotAttributeEnum[BorderStyle]:
  val default = solid
  val label = "Border Style"

  override def valuesWithLabel = Array(
    ("Solid", solid),
    ("Dashed", dashed),
    ("Dotted", dotted)
  )

enum CornerStyle derives CanEqual:
  case rounded, diagonals, normal

/** Part of the "Style" attribute
  */
object CornerStyle extends DotAttributeEnum[CornerStyle]:
  val default = normal
  val label = "Corner"

  override def valuesWithLabel = Array(
    ("Normal", normal),
    ("Rounded", rounded),
    ("Diagonals", diagonals),
  )
