package org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes

import scala.util.Try

enum Rankdir:
  case TB, LR, BT, RL

object Rankdir extends DotAttributeEnum[Rankdir]:
  val default = TB
  val label = "Direction"

enum Splines:
  case line, spline, polyline, ortho, curved, `true`, `false`, none

object Splines extends DotAttributeEnum[Splines]:
  val default = spline
  val label = "Splines"

enum Overlap:
  case `false`, scale, compress

object Overlap extends DotAttributeEnum[Overlap]:
  val default = `false`
  val label = "Overlap"

object Label extends DotAttributeSimple[String]:
  val label = "Label"
  val default = ""
  override val placeholderText = "Enter label here"

object BgColor extends DotAttributeSimple[String]:
  val label = "Background Color"
  val default = "#ffffff"
  override val placeholderText = "Enter background color here"

object FontName extends DotAttributeEnum[String]:
  val label = "Font Name"
  val default = "Times New Roman"
  override val placeholderText = "Enter font name here"
  val values: Array[String] =
    Array("Arial", "Courier New", "Georgia", "Lucida Console", "Times New Roman", "Verdana")

object FontColor extends DotAttributeSimple[String]:
  val label = "Font Color"
  val default = "#000000"
  override val placeholderText = "Enter font color here"

enum Ordering:
  case out, in

object Ordering extends DotAttributeEnum[Ordering]:
  val default = out
  val label = "Ordering"

enum Shape:
  case box, polygon, ellipse, oval, circle, point, egg, triangle, plaintext, plain, diamond, trapezium, parallelogram,
    house, pentagon, hexagon, septagon, octagon, doublecircle, doubleoctagon, tripleoctagon, invtriangle, invtrapezium,
    invhouse, Mdiamond, Msquare, Mcircle, Mrecord, rect, rectangle, square, star, underline, cylinder, note, tab,
    folder, box3d,
    component, promoter, cds, terminator, utr, primersite, restrictionsite, fivepoverhang, threepoverhang, noverhang,
    assembly, signature, insulator, ribosite, rnastab, proteasesite, proteinstab, rarrow, larrow, lpromoter, rpromoter

object Shape extends DotAttributeEnum[Shape]:
  val default = ellipse
  val label = "Shape"

object Orientation extends DotAttributeSimple[Double]:
  val label = "Orientation"
  val default = 0.0
  override val placeholderText = "Enter orientation here"

object RankSep extends DotAttributeSimple[Double]:
  val label = "Rank Separation"
  val default = 0.5
  override val placeholderText = "Enter rank separation here"

object NodeSep extends DotAttributeSimple[Double]:
  val label = "Node Separation"
  val default = 0.25
  override val placeholderText = "Enter node separation here"

object PenWidth extends DotAttributeSimple[Double]:
  val label = "Pen Width"
  val default = 1.0
  override val placeholderText = "Enter pen width here"

enum LabelLoc:
  case t, c, b


object LabelLoc extends DotAttributeEnum[LabelLoc]:
  val default = c
  val label = "Label Location"
  override val valuesWithLabel: Array[(String, LabelLoc)] = Array(
    ("Top", t),
    ("Center", c),
    ("Bottom", b)
  )



object Peripheries extends DotAttributeSimple[Int]:
  val label = "Peripheries"
  val default = 1
  override val placeholderText = "Enter peripheries here"

object Regular extends DotAttributeSimple[Boolean]:
  val default = false
  val label = "Regular"

object Sides extends DotAttributeSimple[Int]:
  val default = 4
  val label = "Sides"

enum Style:
  case dashed, dotted, solid, bold, invis, filled, diagonals, rounded, striped, wedged, tapered

object Style extends DotAttributeEnum[Style]:
  val default = solid
  val label = "Style"

enum Layout:
  case dot, neato, fdp, sfdp, twopi, circo, osage

object Layout extends DotAttributeEnum[Layout]:
  val default = dot
  val label = "Layout"

//object Rotate extends DotAttributeSimple[Double]:
//  val label = "Rotate"
//  val default = 0.0
//  override val placeholderText = "Enter rotation here"

enum ArrowType:
  case normal, inv, dot, invdot, odot, invodot, none, tee, empty, invempty, diamond, odiamond, ediamond, crow, box,
    obox, open, halfopen, vee

object ArrowHead extends DotAttributeEnum[ArrowType]:
  val default = ArrowType.normal
  val label = "Arrow head"
  def values: Array[ArrowType] = ArrowType.values

object ArrowTail extends DotAttributeEnum[ArrowType]:
  val default = ArrowType.normal
  val label = "Arrow tail"
  def values: Array[ArrowType] = ArrowType.values

enum DirType:
  case forward, back, both, none

object Dir extends DotAttributeEnum[DirType]:
  val default = DirType.forward
  val label = "Direction"
  val values: Array[DirType] = DirType.values

object Color extends DotAttributeSimple[String]:
  val label = "Border Color"
  val default = "#ffffff"
  override val placeholderText = "Enter color here"

object FillColor extends DotAttributeSimple[String]:
  val label = "Fill Color"
  val default = "#D3D3D3" // lightgrey
  override val placeholderText = "Enter fill color here"

object FontSize extends DotAttributeSimple[Double]:
  val label = "Font Size"
  override val placeholderText = "Enter font size here"
  val default = 14.0

object Decorate extends DotAttributeSimple[Boolean]:
  val label = "Decorate"
  val default = false

object Weight extends DotAttributeSimple[Double]:
  val label = "Weight"
  val default = 1.0
  override val placeholderText = "Enter weight here"

object Pad extends DotAttributeSimple[Double]:
  val label = "Padding"
  val default = 0.0555

object URL extends DotAttributeSimple[String]:
  override val attrId = "URL"
  val label = "URL"
  val default = ""
  override val placeholderText = "Enter URL here"

object Constraint extends DotAttributeSimple[Boolean]:
  val label = "Constraint"
  val default = true

// ----------------------------------
//
// ----------------------------------

enum ImageScale:
  case `false`, `true`, width, height, both

object ImageScale extends DotAttributeEnum[ImageScale]:
  val default = `false`
  val label = "Image Scale"

// Type aliases for numeric and string types
type Points = Double

type LayerRange = String
type StyleSpec = String
type FilePath = String

// Case classes for compound types
case class Point(x: Double, y: Double)

case class PointList(points: List[Point])

class NodeDimension private (val value: Double):
  require(value > 0, "Dimension must be positive")

object NodeDimension:
  def apply(value: Double): Option[NodeDimension] =
    Try(new NodeDimension(value)).toOption

// Width/Height defaults
object Width:
  val default = 0.75

object Height:
  val default = 0.5

// Boolean attributes with defaults
object FixedSize:
  val default = false

object NoJustify:
  val default = false

object Pin:
  val default = false

// Numeric attributes with defaults
object Distortion:
  val default = 0.0

object Skew:
  val default = 0.0

object ZCoord:
  val default = 0.0

// Margin type with default
case class Margin(x: Double, y: Double)

object Margin:
  val default = Margin(0.11, 0.055)

  def fromString(s: String): Option[Margin] =
    s.split(",") match
      case Array(x, y) => Try(Margin(x.toDouble, y.toDouble)).toOption
      case _           => None
