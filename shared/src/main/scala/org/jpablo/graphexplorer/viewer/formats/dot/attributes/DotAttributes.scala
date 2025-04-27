package org.jpablo.graphexplorer.viewer.formats.dot.attributes

import org.jpablo.graphexplorer.viewer.models.AttributeId

import scala.util.Try

enum ArrowType derives CanEqual:
  case none,
    vee,
    normal,
    onormal,
    odot,
    box,
    crow,
    curve,
    diamond,
    dot,
    inv,
    tee,
    obox,
    odiamond,
    halfvee

object ArrowType:
  val synonyms = Map(
  )

object ArrowHead extends DotAttributeEnum[ArrowType]:
  val default                  = ArrowType.vee
  val label                    = "Arrow head"
  def values: Array[ArrowType] = ArrowType.values

object ArrowSize extends DotAttributeSimple[Double]:
  val label                    = "Arrow Size"
  val default                  = 1.0
  override val placeholderText = "Enter arrow size here"

object ArrowTail extends DotAttributeEnum[ArrowType]:
  val default                  = ArrowType.none // default for dir=forward
  val label                    = "Arrow tail"
  def values: Array[ArrowType] = ArrowType.values

object BgColor extends DotAttributeSimple[String]:
  val label                    = "Background color"
  val default                  = "#ffffff"
  override val placeholderText = "Enter background color here"

object ClusterLabelLoc extends GroupLabelLocT:
  import GroupLabelLoc.*
  def default = t

enum ClusterStyle:
  case filled, striped, rounded

object ClusterStyle extends DotAttributeEnum[ClusterStyle]:
  override def attrId = Style.attrId
  val default         = filled // This seems incorrect as the default is empty
  val label           = "Cluster Style"

/** Border color
  */
object Color extends DotAttributeSimple[String]:
  val label                    = "Border"
  val default                  = "#000000"
  override val placeholderText = "Enter color here"

object Concentrate extends DotAttributeSimple[Boolean]:
  val label   = "Concentrate"
  val default = false

object Constraint extends DotAttributeSimple[Boolean]:
  val label                 = "Constraint"
  val default               = true
  override val validLayouts = Set(Layout.dot)

object Decorate extends DotAttributeSimple[Boolean]:
  val label   = "Decorate"
  val default = false

object Dir extends DotAttributeEnum[DirType]:
  val default                = DirType.forward
  val label                  = "Direction"
  val values: Array[DirType] = DirType.values
  override def valuesWithLabel = Array(
    ("Forward", DirType.forward),
    ("Back", DirType.back),
    ("Both", DirType.both),
    ("None", DirType.none)
  )

enum DirType:
  case forward, back, both, none

object Distortion:
  val default = 0.0

/** aka Border style
  */
object EdgeStyle extends DotAttributeEnum[EdgeStyle]:
  override def attrId = Style.attrId
  val default         = solid
  val label           = "Border Style"
  override def valuesWithLabel = Array(
    ("Invisible", invis),
    ("Dashed", dashed),
    ("Dotted", dotted),
    ("Solid", solid),
    ("Bold", bold),
    ("Tapered", tapered)
  )

enum EdgeStyle derives CanEqual:
  case dashed, dotted, solid, bold, invis, tapered

object FillColor extends DotAttributeSimple[String]:
  val label   = "Fill"
  val none    = "none"
  val default = none // default for nodes
  override val placeholderText = "Enter fill color here"

object FixedSize:
  val default = false

object FontColor extends DotAttributeSimple[String]:
  val label                    = "Font Color"
  val default                  = "#000000"
  override val placeholderText = "Enter font color here"

object FontName extends DotAttributeSimple[String]:
  val label                    = "Font Family"
  val default                  = "Times New Roman"
  override val placeholderText = "Enter font name here"
  override val values: Array[String] =
    Array("Arial", "Courier New", "Georgia", "Lucida Console", "Times New Roman", "Verdana")

object FontSize extends DotAttributeSimple[Double]:
  val label                    = "Font Size"
  override val placeholderText = "Enter font size here"
  val default                  = 14.0

enum GraphType derives CanEqual:
  case graph, digraph

object GraphType extends DotAttributeEnum[GraphType]:
  val default = digraph
  val label   = "Graph Type"
  override val valuesWithLabel = Array(
    ("Undirected graph", graph),
    ("Directed graph", digraph)
  )

enum GroupLabelLoc:
  case t, b

trait GroupLabelLocT extends DotAttributeEnum[GroupLabelLoc]:
  import GroupLabelLoc.*
  override val attrId = AttributeId("labelloc")
  val label           = "Vertical alignment"
  def values          = Array(t, b)
  override val valuesWithLabel = Array(
    ("Top", t),
    ("Bottom", b)
  )

object Height:
  val default = 0.5

object Id extends DotAttributeSimple[String]:
  val label   = "Id"
  val default = ""

enum ImageScale:
  case `false`, `true`, width, height, both

object ImageScale extends DotAttributeEnum[ImageScale]:
  val default = `false`
  val label   = "Image Scale"

object Label extends DotAttributeSimple[String]:
  val label                    = "Label"
  val default                  = ""
  override val placeholderText = "Enter label here"

object LabelJust extends DotAttributeEnum[LabelJust]:
  val default = c
  val label   = "Horizontal alignment"
  override val valuesWithLabel = Array(
    ("Left", l),
    ("Center", c),
    ("Right", r)
  )

enum LabelJust:
  case l, c, r

enum Layout:
  case dot, neato, fdp, sfdp, twopi, circo, osage, patchwork

object Layout extends DotAttributeEnum[Layout]:
  val default = dot
  val label   = "Layout"
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

case class Margin(x: Double, y: Double)

object Margin:
  val default = Margin(0.11, 0.055)

  def fromString(s: String): Option[Margin] =
    s.split(",") match
      case Array(x, y) => Try(Margin(x.toDouble, y.toDouble)).toOption
      case _           => None

class NodeDimension private (val value: Double):
  require(value > 0, "Dimension must be positive")

object NodeDimension:
  def apply(value: Double): Option[NodeDimension] =
    Try(new NodeDimension(value)).toOption

enum NodeLabelLoc:
  case t, c, b

/** Vertical alignment of node labels
  */
object NodeLabelLoc extends DotAttributeEnum[NodeLabelLoc]:
  override val attrId = AttributeId("labelloc")
  def default         = c
  val label           = "Vertical alignment"
  override val valuesWithLabel = Array(
    ("Top", t),
    ("Center", c),
    ("Bottom", b)
  )

object NodeSep extends DotAttributeSimple[Double]:
  val label                    = "Node Separation"
  val default                  = 0.25
  override val placeholderText = "Enter node separation here"

enum NodeStyle derives CanEqual:
  case dashed, dotted, solid, bold, invis, striped, wedged, diagonals, rounded

object NodeStyle extends DotAttributeEnum[NodeStyle]:
  override def attrId = Style.attrId
  val default         = solid
  val label           = "Node Style"
  // part of the DOT style attribute but explicitly excluded from the enum
  val filled = Style.filled
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

object NoJustify:
  val default = false

object Ordering extends DotAttributeEnum[Ordering]:
  val default = out
  val label   = "Ordering"

enum Ordering:
  case out, in

object Orientation extends DotAttributeSimple[Double]:
  val label                    = "Orientation"
  val default                  = 0.0
  override val placeholderText = "Enter orientation here"

enum Overlap:
  case `false`, scale, compress

object Overlap extends DotAttributeEnum[Overlap]:
  val default = `false`
  val label   = "Overlap"

object Pad extends DotAttributeSimple[Double]:
  val label   = "Padding"
  val default = 0.0555

object PenColor extends DotAttributeSimple[String]:
  val label                    = "Border"
  val default                  = "#000000" // default for nodes
  override val placeholderText = "Enter border color here"

object PenWidth extends DotAttributeSimple[Double]:
  val label                    = "Width"
  val default                  = 1.0
  override val placeholderText = "Enter pen width here"

object Peripheries extends DotAttributeSimple[Int]:
  val label                    = "Peripheries"
  val default                  = 1
  override val placeholderText = "Enter peripheries here"

object Pin:
  val default = false

case class Point(x: Double, y: Double)

case class PointList(points: List[Point])

enum Rankdir derives CanEqual:
  case TB, LR, BT, RL

object Rankdir extends DotAttributeEnum[Rankdir]:
  val default               = TB
  val label                 = "Diagram Direction"
  override val validLayouts = Set(Layout.dot)
  override val valuesWithLabel = Array(
    ("Top to Bottom", TB),
    ("Left to Right", LR),
    ("Bottom to Top", BT),
    ("Right to Left", RL)
  )

object RankSep extends DotAttributeSimple[Double]:
  val label                    = "Rank Separation"
  val default                  = 0.5
  override val validLayouts    = Set(Layout.dot, Layout.twopi)
  override val placeholderText = "Enter rank separation here"

object Regular extends DotAttributeSimple[Boolean]:
  val default = false
  val label   = "Regular"

object RootGraphLabelLoc extends GroupLabelLocT:
  import GroupLabelLoc.*
  def default = b

enum Shape derives CanEqual:
  case box, polygon, ellipse, oval, circle, point, egg, triangle, plaintext, plain, diamond, trapezium, parallelogram,
    house, pentagon, hexagon, septagon, octagon, doublecircle, doubleoctagon, tripleoctagon, invtriangle, invtrapezium,
    invhouse, Mdiamond, Msquare, Mcircle, Mrecord, rect, rectangle, square, star, underline, cylinder, note, tab,
    folder, box3d, none,
    component, promoter, cds, terminator, utr, primersite, restrictionsite, fivepoverhang, threepoverhang, noverhang,
    assembly, signature, insulator, ribosite, rnastab, proteasesite, proteinstab, rarrow, larrow, lpromoter, rpromoter

object Shape extends DotAttributeEnum[Shape]:
  val default = box
  val label   = "Shape"

  val basicShapes      = List(box, ellipse, circle, diamond)
  val polygonShapes    = List(polygon, pentagon, hexagon, septagon, octagon)
  val invShapes        = List(invtriangle, invtrapezium, invhouse)
  val mShapes          = List(Mdiamond, Msquare, Mcircle, Mrecord)
  val recordShapes     = List(Mrecord, tab, note, tab, folder, box3d)
  val invRecordShapes  = List(invhouse, invtriangle, invtrapezium)
  val invRecordMShapes = List(invhouse, invtriangle, invtrapezium)
  val otherShapes = List(
    component,
    promoter,
    cds,
    terminator,
    utr,
    primersite,
    restrictionsite,
    fivepoverhang,
    threepoverhang,
    noverhang,
    assembly,
    signature,
    insulator,
    ribosite,
    rnastab,
    proteasesite,
    proteinstab,
    rarrow,
    larrow,
    lpromoter,
    rpromoter
  )
  val allShapes =
    basicShapes ++ polygonShapes ++ invShapes ++ mShapes ++ recordShapes ++ invRecordShapes ++ invRecordMShapes ++ otherShapes

  val synonyms = Map(
    rectangle     -> box,
    rect          -> box,
    none          -> plaintext,
    oval          -> ellipse,
    pentagon      -> polygon,
    hexagon       -> polygon,
    septagon      -> polygon,
    octagon       -> polygon,
    doublecircle  -> circle,
    doubleoctagon -> polygon,
    tripleoctagon -> polygon
  )

object Sides extends DotAttributeSimple[Int]:
  val default = 5
  val label   = "Sides"
  // only show when Shape.polygon is selected

object Size extends DotAttributeSimple[Double]:
  val label   = "Size"
  val default = 0.0

object Skew:
  val default = 0.0

enum Splines:
  case line, spline, polyline, ortho, curved, `true`, `false`, none

object Splines extends DotAttributeEnum[Splines]:
  val default = spline
  val label   = "Curve style"
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

enum Style:
  case filled, dashed, dotted, solid, bold, invis, diagonals, rounded, striped, wedged, tapered

object Style extends DotAttributeEnum[Style]:
  val default = solid
  val label   = "Style"

object URL extends DotAttributeSimple[String]:
  override val attrId          = AttributeId("URL")
  val label                    = "URL"
  val default                  = ""
  override val placeholderText = "Enter URL here"

object Weight extends DotAttributeSimple[Double]:
  val label                    = "Weight"
  val default                  = 1.0
  override val placeholderText = "Enter weight here"

object Width:
  val default = 0.75

object XLabel extends DotAttributeSimple[String]:
  val label                    = "External label"
  val default                  = ""
  override val placeholderText = "Enter label here"

object Xlp extends DotAttributeSimple[Double]:
  val label                    = "External pos"
  val default                  = 1
  override val placeholderText = "External label position"

object ZCoord:
  val default = 0.0
