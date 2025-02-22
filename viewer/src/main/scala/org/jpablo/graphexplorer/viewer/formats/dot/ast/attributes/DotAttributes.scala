package org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes

import scala.util.Try

enum Rankdir:
  case TB, LR, BT, RL

object Rankdir extends DotAttributeEnum[Rankdir]:
  val default = TB
  val label = "Direction"
  override val valuesWithLabel = Array(
    ("Top to Bottom", TB),
    ("Left to Right", LR),
    ("Bottom to Top", BT),
    ("Right to Left", RL)
  )

enum Splines:
  case line, spline, polyline, ortho, curved, `true`, `false`, none

object Splines extends DotAttributeEnum[Splines]:
  val default = spline
  val label = "Curve style"
  override val valuesWithLabel = Array(
    ("Spline", spline),
    ("Line", line),
    ("Polyline", polyline),
    ("Orthogonal", ortho),
    ("Curved", curved),
    // ("True", `true`),
    // ("False", `false`),
    ("None", none)
  )

enum Overlap:
  case `false`, scale, compress

object Overlap extends DotAttributeEnum[Overlap]:
  val default = `false`
  val label = "Overlap"

object Label extends DotAttributeSimple[String]:
  val label = "Label"
  val default = ""
  override val placeholderText = "Enter label here"

object XLabel extends DotAttributeSimple[String]:
  val label = "External label"
  val default = ""
  override val placeholderText = "Enter label here"


object Xlp extends DotAttributeSimple[Double]:
  val label = "External pos"
  val default = 1
  override val placeholderText = "External label position"

object BgColor extends DotAttributeSimple[String]:
  val label = "Fill color"
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
    folder, box3d, none,
    component, promoter, cds, terminator, utr, primersite, restrictionsite, fivepoverhang, threepoverhang, noverhang,
    assembly, signature, insulator, ribosite, rnastab, proteasesite, proteinstab, rarrow, larrow, lpromoter, rpromoter

object Shape extends DotAttributeEnum[Shape]:
  val default = ellipse
  val label = "Shape"

  val basicShapes = List(box, ellipse, circle, diamond)
  val polygonShapes = List(polygon, pentagon, hexagon, septagon, octagon)
  val invShapes = List(invtriangle, invtrapezium, invhouse)
  val mShapes = List(Mdiamond, Msquare, Mcircle, Mrecord)
  val recordShapes = List(Mrecord, tab, note, tab, folder, box3d)
  val invRecordShapes = List(invhouse, invtriangle, invtrapezium)
  val invRecordMShapes = List(invhouse, invtriangle, invtrapezium)
  val otherShapes = List(component, promoter, cds, terminator, utr, primersite, restrictionsite, fivepoverhang, threepoverhang, noverhang,
    assembly, signature, insulator, ribosite, rnastab, proteasesite, proteinstab, rarrow, larrow, lpromoter, rpromoter)
  val allShapes = basicShapes ++ polygonShapes ++ invShapes ++ mShapes ++ recordShapes ++ invRecordShapes ++ invRecordMShapes ++ otherShapes

  val synonyms = Map(
    rectangle -> box,
    rect -> box,
    none -> plaintext,
    oval -> ellipse,
    pentagon -> polygon,
    hexagon -> polygon,
    septagon -> polygon,
    octagon -> polygon,
    doublecircle -> circle,
    doubleoctagon -> polygon,
    tripleoctagon -> polygon,
  )

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
  val label = "Vertical pos"
  override val valuesWithLabel = Array(
    ("Top", t),
    ("Center", c),
    ("Bottom", b)
  )

enum LabelJust:
  case l, c, r

object LabelJust extends DotAttributeEnum[LabelJust]:
  val default = c
  val label = "Horizontal pos"
  override val valuesWithLabel = Array(
    ("Left", l),
    ("Center", c),
    ("Right", r)
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
  case dashed, dotted, solid, bold, invis, diagonals, rounded, striped, wedged, tapered

object Style extends DotAttributeEnum[Style]:
  val default = solid
  val label = "Style"
  // part of the DOT style attribute but explicitly excluded from the enum
  val filled = "filled"

enum NodeStyle:
  case dashed, dotted, solid, bold, invis, striped, wedged, diagonals, rounded

object NodeStyle extends DotAttributeEnum[NodeStyle]:
  override def attrId = "style"
  val default = solid
  val label = "Node Style"
  // part of the DOT style attribute but explicitly excluded from the enum
  val filled = "filled"
  override def valuesWithLabel = Array(
    ("Dashed", dashed),
    ("Dotted", dotted),
    ("Solid", solid),
    ("Bold", bold),
    ("Invisible", invis),
    ("Striped", striped),
    ("Wedged", wedged),
    ("Diagonals", diagonals),
    ("Rounded", rounded)
  )

enum EdgeStyle:
  case dashed, dotted, solid, bold, invis, tapered

object EdgeStyle extends DotAttributeEnum[EdgeStyle]:
  override def attrId = "style"
  val default = solid
  val label = "Edge Style"
  override def valuesWithLabel = Array(
    ("Dashed", dashed),
    ("Dotted", dotted),
    ("Solid", solid),
    ("Bold", bold),
    ("Invisible", invis),
    ("Tapered", tapered)
  )

enum ClusterStyle:
  case filled, striped, rounded

object ClusterStyle extends DotAttributeEnum[ClusterStyle]:
  override def attrId = "style"
  val default = filled // This seems incorrect as the default is empty
  val label = "Cluster Style"

enum Layout:
  case dot, neato, fdp, sfdp, twopi, circo, osage, patchwork

object Layout extends DotAttributeEnum[Layout]:
  val default = dot
  val label = "Layout"
  override val valuesWithLabel = Array(
    ("Hierarchical", dot),
    ("Spring model", neato),
    ("Force-directed placement", fdp),
    ("Multilevel force-directed placement", sfdp),
    ("Radial", twopi),
    ("Circular", circo),
    ("Clustered", osage),
    ("Squarified treemap", patchwork)
  )

//object Rotate extends DotAttributeSimple[Double]:
//  val label = "Rotate"
//  val default = 0.0
//  override val placeholderText = "Enter rotation here"

enum ArrowType:
  case
    box,
    crow,
    curve,
    diamond,
    dot,
    icurve,
    inv,
    none,
    normal,
    tee,
    vee,

    obox,
    odiamond,
    odot,
    oinv,
    onormal,

    halfvee

object ArrowType:
  val synonyms = Map(
  )


object ArrowHead extends DotAttributeEnum[ArrowType]:
  val default = ArrowType.normal // default for dir=forward
  val label = "Arrow head"
  def values: Array[ArrowType] = ArrowType.values

object ArrowTail extends DotAttributeEnum[ArrowType]:
  val default = ArrowType.none // default for dir=forward
  val label = "Arrow tail"
  def values: Array[ArrowType] = ArrowType.values

enum DirType:
  case forward, back, both, none

object Dir extends DotAttributeEnum[DirType]:
  val default = DirType.forward
  val label = "Direction"
  val values: Array[DirType] = DirType.values
  override def valuesWithLabel = Array(
    ("Forward", DirType.forward),
    ("Back", DirType.back),
    ("Both", DirType.both),
    ("None", DirType.none)
  )

object Color extends DotAttributeSimple[String]:
//  val label = "Border Color"
  val label = "Color"
  val default = "#000000"
  override val placeholderText = "Enter color here"

object FillColor extends DotAttributeSimple[String]:
  val label = "Fill Color"
  val default = "#d3d3d3" // default for nodes
  override val placeholderText = "Enter fill color here"

object PenColor extends DotAttributeSimple[String]:
  val label = "Pen Color"
  val default = "#000000" // default for nodes
  override val placeholderText = "Enter border color here"

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
