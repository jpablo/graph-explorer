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
  val synonyms =
    Map(
    )

object ArrowHead extends DotAttributeEnum[ArrowType]:
  val default                  = ArrowType.vee
  val label                    = "Head"
  def values: Array[ArrowType] = ArrowType.values

object ArrowSize extends DotAttributeSimple[Double]:
  val label                    = "Arrow Size"
  val default                  = 1.0
  override val placeholderText = "Enter arrow size here"

object ArrowTail extends DotAttributeEnum[ArrowType]:
  val default                  = ArrowType.none // default for dir=forward
  val label                    = "Tail"
  def values: Array[ArrowType] = ArrowType.values

object BgColor extends DotAttributeSimple[String]:
  val label                    = "Background color"
  val default                  = "#ffffff"
  override val placeholderText = "Enter background color here"

object Cluster extends DotAttributeEnum[Boolean]:
  def label: String          = "Cluster"
  def default: Boolean       = false
  def values: Array[Boolean] = Array(true, false)

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
  val label                    = "Fill"
  val none                     = "none"
  val default                  = none        // default for nodes
  val filledDefault            = "lightgrey" // default for nodes with style="filled"
  override val placeholderText = "Enter fill color here"

object FixedSize extends DotAttributeSimple[Boolean]:
  val default = false
  def label   = "Fixed size"

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

  def isDirected: Boolean =
    this == digraph

object GraphType extends DotAttributeEnum[GraphType]:
  val default = digraph
  val label   = "Graph Type"
  override val valuesWithLabel = Array(
    ("Undirected graph", graph),
    ("Directed graph", digraph)
  )

  def fromBoolean(isDirected: Boolean): GraphType =
    if isDirected then digraph else graph

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

/** [[https://graphviz.org/docs/attrs/height]]
  *
  * In inches.
  */
object Height extends DotAttributeSimple[Double]:
  val default = 0.5
  val min     = 0.02
  def label   = "Height"

enum CompassPoint derives CanEqual:
  case n, ne, e, se, s, sw, w, nw, c

object CompassPoint:
  val valuesWithLabel = Array(
    ("North", n),
    ("North East", CompassPoint.ne),
    ("East", e),
    ("South East", se),
    ("South", s),
    ("South West", sw),
    ("West", w),
    ("North West", nw),
    ("Center", c)
  )

object HeadPort extends DotAttributeEnum[CompassPoint]:
  def label: String               = "Head Port"
  def default: CompassPoint       = CompassPoint.c
  def values: Array[CompassPoint] = CompassPoint.values
  override val valuesWithLabel    = CompassPoint.valuesWithLabel

object TailPort extends DotAttributeEnum[CompassPoint]:
  def label: String               = "Tail Port"
  def default: CompassPoint       = CompassPoint.c
  def values: Array[CompassPoint] = CompassPoint.values
  override val valuesWithLabel    = CompassPoint.valuesWithLabel

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

object Margin extends DotAttributeSimple[Margin]:
  val label                    = "Margin"
  val default                  = Margin(0.11, 0.055)
  override val placeholderText = "Enter margin as x,y"

  override def fromString(s: String): Option[Margin] =
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

object NoJustify extends DotAttributeSimple[Boolean]:
  val label   = "No Justify"
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

enum RankType derives CanEqual:
  case none, same, min, source, max, sink

object Rank extends DotAttributeEnum[RankType]:
  def label: String           = "Rank"
  def default: RankType       = RankType.none
  def values: Array[RankType] = RankType.values

  override def valuesWithLabel: Array[(String, RankType)] =
    Array(
      ("None", RankType.none),
      ("Same", RankType.same),
      ("Minimum", RankType.min),
      ("Source", RankType.source),
      ("Maximum", RankType.max),
      ("Sink", RankType.sink)
    )

object Regular extends DotAttributeSimple[Boolean]:
  val default = false
  val label   = "Regular"

object RootGraphLabelLoc extends GroupLabelLocT:
  import GroupLabelLoc.*
  def default = b

enum Shape derives CanEqual:
  // Basic common shapes
  case box
  case rectangle
  case rect
  case square
  case circle
  case ellipse
  case oval
  case point
  case none

  // Polygons
  case polygon
  case pentagon
  case hexagon
  case septagon
  case octagon

  case triangle
  case diamond
  case star

  // Inverted shapes
  case invtriangle
  case invtrapezium
  case invhouse

  // Quadrilaterals
  case trapezium
  case parallelogram
  case house

  // Multi/compound shapes
  case doublecircle
  case doubleoctagon
  case tripleoctagon

  // Modified shapes (M-prefixed)
  case Mdiamond
  case Msquare
  case Mcircle
  case Mrecord

  // Text and records
  case plaintext
  case plain
  case record
  case underline

  // 3D and container shapes
  case box3d
  case cylinder
  case note
  case tab
  case folder

  // Arrows
  case rarrow
  case larrow

  // Component/system shapes
  case component
  case egg
  case signature
  case assembly

  // Biological/genetic elements
  case promoter
  case lpromoter
  case rpromoter
  case cds
  case terminator
  case utr
  case insulator
  case ribosite
  case rnastab
  case proteasesite
  case proteinstab

  // Molecular biology sites
  case primersite
  case restrictionsite
  case fivepoverhang
  case threepoverhang
  case noverhang

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

object Width extends DotAttributeSimple[Double]:
  val default = 0.75
  val min     = 0.01
  def label   = "Width"

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

// Position and Layout Attributes

object Pos extends DotAttributeSimple[String]:
  val label   = "Position"
  val default = ""
  override val placeholderText = "Enter position (x,y)"

object Lp extends DotAttributeSimple[String]:
  val label   = "Label Position"
  val default = ""
  override val placeholderText = "Enter label position (x,y)"

object LHeight extends DotAttributeSimple[Double]:
  val label   = "Label Height"
  val default = 0.0

object LWidth extends DotAttributeSimple[Double]:
  val label   = "Label Width"
  val default = 0.0

// Visual and Styling Attributes

object Class extends DotAttributeSimple[String]:
  val label                    = "CSS Class"
  val default                  = ""
  override val placeholderText = "Enter CSS class name"

object ColorScheme extends DotAttributeSimple[String]:
  val label                    = "Color Scheme"
  val default                  = ""
  override val placeholderText = "Enter color scheme"

object Target extends DotAttributeSimple[String]:
  val label                    = "URL Target"
  val default                  = ""
  override val placeholderText = "Enter target window name"

object Tooltip extends DotAttributeSimple[String]:
  val label                    = "Tooltip"
  val default                  = ""
  override val placeholderText = "Enter tooltip text"

// Image Attributes

object Image extends DotAttributeSimple[String]:
  val label                    = "Image"
  val default                  = ""
  override val placeholderText = "Enter image file path"

object ImagePath extends DotAttributeSimple[String]:
  val label                    = "Image Path"
  val default                  = ""
  override val placeholderText = "Enter image search path"

object ImagePos extends DotAttributeSimple[String]:
  val label   = "Image Position"
  val default = "mc"
  override val placeholderText = "Enter image position (e.g., mc, tl, br)"

// Geometry Attributes

object Rects extends DotAttributeSimple[String]:
  val label   = "Rectangles"
  val default = ""
  override val placeholderText = "Record field rectangles (write only)"

object Area extends DotAttributeSimple[Double]:
  val label   = "Area"
  val default = 1.0

object Vertices extends DotAttributeSimple[String]:
  val label   = "Vertices"
  val default = ""
  override val placeholderText = "Polygon vertex coordinates"

// Text and Label Attributes

object LabelDistance extends DotAttributeSimple[Double]:
  val label   = "Label Distance"
  val default = 1.0

object LabelFloat extends DotAttributeSimple[Boolean]:
  val label   = "Label Float"
  val default = false

object LabelFontColor extends DotAttributeSimple[String]:
  val label                    = "Label Font Color"
  val default                  = "#000000"
  override val placeholderText = "Enter label font color"

object LabelFontName extends DotAttributeSimple[String]:
  val label                    = "Label Font Name"
  val default                  = "Times-Roman"
  override val placeholderText = "Enter label font name"

// Layout Algorithm Attributes

object Normalize extends DotAttributeSimple[Boolean]:
  val label   = "Normalize"
  val default = false

object Start extends DotAttributeSimple[String]:
  val label                    = "Start"
  val default                  = ""
  override val placeholderText = "Random seed for layout"

object Ratio extends DotAttributeSimple[String]:
  val label                    = "Aspect Ratio"
  val default                  = ""
  override val placeholderText = "Enter aspect ratio or 'fill', 'compress', 'auto'"

// Edge-specific Attributes

object Len extends DotAttributeSimple[Double]:
  val label   = "Length"
  val default = 1.0

object ForceLabels extends DotAttributeSimple[Boolean]:
  val label   = "Force Labels"
  val default = true

object Layer extends DotAttributeSimple[String]:
  val label                    = "Layer"
  val default                  = ""
  override val placeholderText = "Enter layer name or range"

object SameHead extends DotAttributeSimple[String]:
  val label                    = "Same Head"
  val default                  = ""
  override val placeholderText = "Group edges with same head"

object SameTail extends DotAttributeSimple[String]:
  val label                    = "Same Tail"
  val default                  = ""
  override val placeholderText = "Group edges with same tail"

object TailClip extends DotAttributeSimple[Boolean]:
  val label   = "Tail Clip"
  val default = true

object TailTarget extends DotAttributeSimple[String]:
  val label                    = "Tail Target"
  val default                  = ""
  override val placeholderText = "Target for tail URL"

object TailTooltip extends DotAttributeSimple[String]:
  val label                    = "Tail Tooltip"
  val default                  = ""
  override val placeholderText = "Tooltip for edge tail"

object TailURL extends DotAttributeSimple[String]:
  override val attrId          = AttributeId("tailURL")
  val label                    = "Tail URL"
  val default                  = ""
  override val placeholderText = "URL for edge tail"

// Debug Attributes

object ShowBoxes extends DotAttributeSimple[Int]:
  val label   = "Show Boxes"
  val default = 0

object TailLp extends DotAttributeSimple[String]:
  override val attrId          = AttributeId("tail_lp")
  val label                    = "Tail Label Position"
  val default                  = ""
  override val placeholderText = "Tail label position (x,y)"

// dot_json structural keys
//
// Graphviz's json output carries these next to the real attributes: `_gvid` is an
// object's declaration index, `name` its id, and `head`/`tail` the `_gvid` of an
// edge's endpoints. DOT accepts none of them as input — they are engine output —
// but the SimpleGraph converter keeps them as attributes (declaration order and
// endpoint resolution both read them), so they need ids like everything else.

object GvId extends DotAttributeSimple[Int]:
  override val attrId = AttributeId("_gvid")
  val label           = "Graphviz Id"
  val default         = 0

object Name extends DotAttributeSimple[String]:
  val label   = "Name"
  val default = ""

object Head extends DotAttributeSimple[Int]:
  val label   = "Head Node Id"
  val default = 0

object Tail extends DotAttributeSimple[Int]:
  val label   = "Tail Node Id"
  val default = 0
