package org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs

import scala.scalajs.js


@js.native
trait Graph extends js.Object:
  val name: js.UndefOr[String]                  = js.native
  val strict: js.UndefOr[Boolean]               = js.native
  val directed: js.UndefOr[Boolean]             = js.native
  val graphAttributes: js.UndefOr[Attributes]   = js.native
  val nodeAttributes: js.UndefOr[Attributes]    = js.native
  val edgeAttributes: js.UndefOr[Attributes]    = js.native
  val nodes: js.UndefOr[js.Array[Node]]         = js.native
  val edges: js.UndefOr[js.Array[Edge]]         = js.native
  val subgraphs: js.UndefOr[js.Array[Subgraph]] = js.native
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

  def getEdgePos(graph: Graph): Map[String, String] =
    val edgePositions = scala.collection.mutable.Map[String, String]()
    
    // Create a map from _gvid to name for node lookup
    val nodeMap = graph.objects.map { objects =>
      objects.map(obj => obj._gvid.get -> obj.name.getOrElse("unknown")).toMap
    }.getOrElse(Map.empty)
    
    // Collect from main graph edges
    graph.edges.foreach { edges =>
      edges.foreach { edge =>
        edge.pos.foreach { pos =>
          // Convert numeric gvids to names if possible
          val tailName = edge.tail match {
            case i: Int => nodeMap.getOrElse(i, i.toString)
            case s: String => s
          }
          val headName = edge.head match {
            case i: Int => nodeMap.getOrElse(i, i.toString) 
            case s: String => s
          }
          
          val edgeId = edge.id.getOrElse(s"$tailName->$headName")
          edgePositions(edgeId) = pos
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
  val _gvid: js.UndefOr[Int]    = js.native
  val name: js.UndefOr[String]  = js.native
  val pos: js.UndefOr[String]   = js.native

object ImageSize:
  def apply(name: String, width: js.Any, height: js.Any): ImageSize =
    js.Dynamic.literal(
      name = name,
      width = width,
      height = height
    ).asInstanceOf[ImageSize]
