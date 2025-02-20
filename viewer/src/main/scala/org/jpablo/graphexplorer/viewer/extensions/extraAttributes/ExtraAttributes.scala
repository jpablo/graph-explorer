package org.jpablo.graphexplorer.viewer.extensions.extraAttributes

import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.{DotAttributeEnum, DotAttributeSimple}

// --- deprecated ---
//enum FillStyle:
//  case NoFill, ColorFill
//
//object FillStyle extends DotAttributeEnum[FillStyle]:
//  val default = NoFill
//  val label = "Fill Style"
//
//  override def valuesWithLabel = Array(
//    ("No Fill", NoFill),
//    ("Color Fill", ColorFill)
//  )

// --- new ---
object FillStyle extends DotAttributeSimple[Boolean]:
  val default = false
  val label = "Filled"
  val filled = "filled"

object BoldStyle extends DotAttributeSimple[Boolean]:
  val default = false
  val label = "Bold"
  val bold = "bold"

enum BorderStyle:
  case solid, dashed, dotted, invis

object BorderStyle extends DotAttributeEnum[BorderStyle]:
  val default = solid
  val label = "Border Style"

  override def valuesWithLabel = Array(
    ("Solid", solid),
    ("Dashed", dashed),
    ("Dotted", dotted),
    ("Invisible", invis)
  )

enum ShapeModStyle:
  case rounded, diagonals, none

object ShapeModStyle extends DotAttributeEnum[ShapeModStyle]:
  val default = none
  val label = "Shape Option"

  override def valuesWithLabel = Array(
    ("Rounded", rounded),
    ("Diagonals", diagonals),
    ("None", none)
  )
