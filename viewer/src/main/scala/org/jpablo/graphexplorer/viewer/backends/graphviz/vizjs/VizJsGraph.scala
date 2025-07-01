package org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs

import scala.scalajs.js
import org.jpablo.graphexplorer.viewer.models.ArrowId

// https://viz-js.com/api/

// This is an alternative input type for the Viz.render methods
// TODO: Verify that the Graph type matches the expected structure
// The "schema" for this is implicitly defined by this file:
// https://github.com/mdaines/viz-js/blob/06d372e9ec650a485f5e6f94030d75f75e796fdd/packages/viz/src/wrapper.mjs
// it traverse the graph and uses lower level functions in the graphviz c library (via WebAssembly)

@js.native
trait Graph extends js.Object:
  val name: js.UndefOr[String]                   = js.native
  val strict: js.UndefOr[Boolean]                = js.native
  val directed: js.UndefOr[Boolean]              = js.native
  val graphAttributes: js.UndefOr[Attributes]    = js.native
  val nodeAttributes: js.UndefOr[Attributes]     = js.native
  val edgeAttributes: js.UndefOr[Attributes]     = js.native
  val nodes: js.UndefOr[js.Array[Node]]          = js.native
  val edges: js.UndefOr[js.Array[Edge]]          = js.native
  val subgraphs: js.UndefOr[js.Array[Subgraph]]  = js.native
  val objects: js.UndefOr[js.Array[GraphObject]] = js.native

object Graph:
  def apply(
      name:            js.UndefOr[String] = js.undefined,
      strict:          js.UndefOr[Boolean] = js.undefined,
      directed:        js.UndefOr[Boolean] = js.undefined,
      graphAttributes: js.UndefOr[Attributes] = js.undefined,
      nodeAttributes:  js.UndefOr[Attributes] = js.undefined,
      edgeAttributes:  js.UndefOr[Attributes] = js.undefined,
      nodes:           js.UndefOr[js.Array[Node]] = js.undefined,
      edges:           js.UndefOr[js.Array[Edge]] = js.undefined,
      subgraphs:       js.UndefOr[js.Array[Subgraph]] = js.undefined
  ): Graph =
    val obj = js.Dynamic.literal()
    name.foreach(obj.updateDynamic("name")(_))
    strict.foreach(obj.updateDynamic("strict")(_))
    directed.foreach(obj.updateDynamic("directed")(_))
    graphAttributes.foreach(obj.updateDynamic("graphAttributes")(_))
    nodeAttributes.foreach(obj.updateDynamic("nodeAttributes")(_))
    edgeAttributes.foreach(obj.updateDynamic("edgeAttributes")(_))
    nodes.foreach(obj.updateDynamic("nodes")(_))
    edges.foreach(obj.updateDynamic("edges")(_))
    subgraphs.foreach(obj.updateDynamic("subgraphs")(_))
    obj.asInstanceOf[Graph]

  def getEdgePos(graph: Graph): Map[String, ArrowPosition] =
    val edgePositions = scala.collection.mutable.Map[String, ArrowPosition]()

    // Create a map from _gvid to name for node lookup
    val nodeMap = graph.objects.map { objects =>
      objects.flatMap { obj =>
        if (obj != null && !js.isUndefined(obj)) {
          obj._gvid.toOption.map(_ -> obj.name.getOrElse("unknown"))
        } else None
      }.toMap
    }.getOrElse(Map.empty)

    // Collect from main graph edges
    graph.edges.foreach { edges =>
      edges.foreach { edge =>
        edge.pos.foreach { pos =>
          ArrowPositionParser.parse(pos).foreach { arrowPos =>
            // Convert numeric gvids to names if possible
            val tailName = edge.tail match {
              case i: Int    => nodeMap.getOrElse(i, i.toString)
              case s: String => s
            }
            val headName = edge.head match {
              case i: Int    => nodeMap.getOrElse(i, i.toString)
              case s: String => s
            }

            val edgeId = edge.id.toOption match {
              case Some(id) =>
                // Try to parse as arrow ID with prefix, fall back to raw ID
                ArrowId.fromSvg(id).map(_.value).getOrElse(id)
              case None => s"$tailName->$headName"
            }
            edgePositions(edgeId) = arrowPos
          }
        }
      }
    }

    edgePositions.toMap

type Attributes = js.Dictionary[String | Double | Boolean | HTMLString]

@js.native
trait HTMLString extends js.Object:
  val html: String = js.native

object HTMLString:
  def apply(html: String): HTMLString =
    js.Dynamic.literal(html = html).asInstanceOf[HTMLString]

@js.native
trait Node extends js.Object:
  val name: String                       = js.native
  val attributes: js.UndefOr[Attributes] = js.native

object Node:
  def apply(name: String, attributes: js.UndefOr[Attributes] = js.undefined): Node =
    val obj = js.Dynamic.literal(name = name)
    attributes.foreach(obj.updateDynamic("attributes")(_))
    obj.asInstanceOf[Node]

@js.native
trait Edge extends js.Object:
  val tail: String | Int                 = js.native
  val head: String | Int                 = js.native
  val attributes: js.UndefOr[Attributes] = js.native
  val pos: js.UndefOr[String]            = js.native
  val id: js.UndefOr[String]             = js.native
  val _gvid: js.UndefOr[Int]             = js.native

object Edge:
  def apply(tail: String, head: String, attributes: js.UndefOr[Attributes] = js.undefined): Edge =
    val obj = js.Dynamic.literal(tail = tail, head = head)
    attributes.foreach(obj.updateDynamic("attributes")(_))
    obj.asInstanceOf[Edge]

@js.native
trait Subgraph extends js.Object:
  val name: js.UndefOr[String]                  = js.native
  val graphAttributes: js.UndefOr[Attributes]   = js.native
  val nodeAttributes: js.UndefOr[Attributes]    = js.native
  val edgeAttributes: js.UndefOr[Attributes]    = js.native
  val nodes: js.UndefOr[js.Array[Node]]         = js.native
  val edges: js.UndefOr[js.Array[Edge]]         = js.native
  val subgraphs: js.UndefOr[js.Array[Subgraph]] = js.native

object Subgraph:
  def apply(
      name:            js.UndefOr[String] = js.undefined,
      graphAttributes: js.UndefOr[Attributes] = js.undefined,
      nodeAttributes:  js.UndefOr[Attributes] = js.undefined,
      edgeAttributes:  js.UndefOr[Attributes] = js.undefined,
      nodes:           js.UndefOr[js.Array[Node]] = js.undefined,
      edges:           js.UndefOr[js.Array[Edge]] = js.undefined,
      subgraphs:       js.UndefOr[js.Array[Subgraph]] = js.undefined
  ): Subgraph =
    val obj = js.Dynamic.literal()
    name.foreach(obj.updateDynamic("name")(_))
    graphAttributes.foreach(obj.updateDynamic("graphAttributes")(_))
    nodeAttributes.foreach(obj.updateDynamic("nodeAttributes")(_))
    edgeAttributes.foreach(obj.updateDynamic("edgeAttributes")(_))
    nodes.foreach(obj.updateDynamic("nodes")(_))
    edges.foreach(obj.updateDynamic("edges")(_))
    subgraphs.foreach(obj.updateDynamic("subgraphs")(_))
    obj.asInstanceOf[Subgraph]

@js.native
trait ImageSize extends js.Object:
  val name: String
  val width: String | Double  = js.native // String | Double
  val height: String | Double = js.native // String | Double

@js.native
trait GraphObject extends js.Object:
  val _gvid: js.UndefOr[Int]   = js.native
  val name: js.UndefOr[String] = js.native
  val pos: js.UndefOr[String]  = js.native

case class Point(x: Double, y: Double)

case class ArrowPosition(
    startPoint:    Point,
    endPoint:      Point,
    controlPoints: List[Point]
)

object ArrowPositionParser:
  def parse(posString: String): Option[ArrowPosition] =
    val coords = posString.trim.split("\\s+").toList
    if coords.length < 2 then None
    else
      var startPoint: Option[Point] = None
      var endPoint: Option[Point]   = None
      val controlPoints             = scala.collection.mutable.ListBuffer[Point]()

      coords.foreach { coord =>
        if coord.startsWith("s,") then
          startPoint = parseCoordinate(coord.drop(2))
        else if coord.startsWith("e,") then
          endPoint = parseCoordinate(coord.drop(2))
        else
          parseCoordinate(coord).foreach(controlPoints += _)
      }

      // If start is missing, take first control point
      if startPoint.isEmpty && controlPoints.nonEmpty then
        startPoint = Some(controlPoints.head)
        controlPoints.remove(0)

      // If end is missing, take LAST control point (paths end at the last coordinate)
      if endPoint.isEmpty && controlPoints.nonEmpty then
        endPoint = Some(controlPoints.last)
        controlPoints.remove(controlPoints.length - 1)

      // Both start and end are required
      (startPoint, endPoint) match
        case (Some(start), Some(end)) => Some(ArrowPosition(start, end, controlPoints.toList))
        case _                        => None

  private def parseCoordinate(coord: String): Option[Point] =
    coord.split(",") match
      case Array(x, y) =>
        try Some(Point(x.toDouble, y.toDouble))
        catch case _: NumberFormatException => None
      case _ => None

object ImageSize:
  def apply(name: String, width: js.Any, height: js.Any): ImageSize =
    js.Dynamic.literal(
      name = name,
      width = width,
      height = height
    ).asInstanceOf[ImageSize]
