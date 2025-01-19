package org.jpablo.graphexplorer.viewer.extensions.extraAttributes

import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.DotAttributeEnum

enum FillStyle:
  case NoFill, ColorFill

object FillStyle extends DotAttributeEnum[FillStyle]:
  val default = NoFill
  val label = "Fill Style"

  override def valuesWithLabel = Array(
    ("No Fill", NoFill),
    ("Color Fill", ColorFill)
  )
