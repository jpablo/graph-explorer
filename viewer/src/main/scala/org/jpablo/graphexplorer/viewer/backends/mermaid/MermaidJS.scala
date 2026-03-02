package org.jpablo.graphexplorer.viewer.backends.mermaid

import scala.scalajs.js
import scala.scalajs.js.annotation.*

/** Scala.js facade for the Mermaid.js library.
  *
  * @see
  *   https://mermaid.js.org/config/usage.html
  */
@js.native
@JSImport("mermaid", JSImport.Default)
object MermaidJS extends js.Object:
  /** Initialize Mermaid with configuration options. */
  def initialize(config: js.Object): Unit = js.native

  /** Parse a diagram definition without rendering. */
  def parse(text: String): js.Promise[ParseResult] = js.native

  /** Render a diagram and return the SVG. */
  def render(id: String, text: String): js.Promise[RenderResult] = js.native

  /** The Mermaid API for advanced operations. */
  val mermaidAPI: MermaidAPI = js.native

/** The Mermaid API object. */
@js.native
trait MermaidAPI extends js.Object:
  /** Get a Diagram object from text, which provides access to the parsed structure. */
  def getDiagramFromText(text: String): js.Promise[Diagram] = js.native

/** Result of mermaid.parse() */
@js.native
trait ParseResult extends js.Object:
  val diagramType: String = js.native

/** Result of mermaid.render() */
@js.native
trait RenderResult extends js.Object:
  /** The rendered SVG string. */
  val svg: String = js.native

  /** Optional function to bind interactive elements after inserting SVG into DOM. */
  val bindFunctions: js.UndefOr[js.Function1[org.scalajs.dom.Element, Unit]] = js.native

/** A parsed Mermaid diagram. */
@js.native
trait Diagram extends js.Object:
  /** The parser object containing the parsed data. */
  val parser: js.UndefOr[DiagramParser] = js.native

  /** The type of diagram (e.g., "flowchart"). */
  val `type`: String = js.native

  /** Diagram database containing parsed elements. */
  val db: js.UndefOr[DiagramYY] = js.native

/** The diagram parser object. */
@js.native
trait DiagramParser extends js.Object:
  /** The yy object containing parsed vertices, edges, etc. */
  val yy: js.UndefOr[DiagramYY] = js.native

/** The yy object with methods to access parsed diagram elements.
  *
  * Note: This is an internal Mermaid API and may change between versions.
  */
@js.native
trait DiagramYY extends js.Object:
  /** Get all vertices (nodes) in the diagram. */
  def getVertices(): js.Dictionary[MermaidVertexJS] = js.native

  /** Get all edges (links) in the diagram. */
  def getEdges(): MermaidEdgesJS = js.native

  /** Get all subgraphs in the diagram. */
  def getSubGraphs(): js.Array[MermaidSubgraphJS] = js.native

  /** Get the current direction (TB, BT, LR, RL). */
  def getDirection(): js.UndefOr[String] = js.native

  /** Get the diagram title from front matter. */
  def getDiagramTitle(): js.UndefOr[String] = js.native

  /** Get the accessible title (alternative method for title). */
  def getAccTitle(): js.UndefOr[String] = js.native

  /** Get all class definitions (classDef) in the diagram. */
  def getClasses(): js.Dictionary[MermaidClassDefJS] = js.native

/** A vertex (node) as returned by Mermaid's parser. */
@js.native
trait MermaidVertexJS extends js.Object:
  val id: String                           = js.native
  val text: String                         = js.native
  val labelType: js.UndefOr[String]        = js.native
  val domId: js.UndefOr[String]            = js.native
  val styles: js.UndefOr[js.Array[String]] = js.native
  val classes: js.UndefOr[js.Array[String]] = js.native
  val `type`: js.UndefOr[String]           = js.native // shape type

/** An edge (link) as returned by Mermaid's parser. */
@js.native
trait MermaidEdgeJS extends js.Object:
  val start: String                 = js.native
  val end: String                   = js.native
  val `type`: js.UndefOr[String]    = js.native // arrow type (e.g., "arrow_point")
  val text: js.UndefOr[String]      = js.native
  val labelType: js.UndefOr[String] = js.native
  val stroke: js.UndefOr[String]    = js.native // "normal", "dotted", "thick"
  val style: js.UndefOr[js.Array[String]] = js.native
  val interpolate: js.UndefOr[String]     = js.native

/** Mermaid flowchart edges collection.
  *
  * Mermaid stores per-edge entries in an array and default link style/interpolate as additional properties on that
  * array object.
  */
@js.native
trait MermaidEdgesJS extends js.Array[MermaidEdgeJS]:
  val defaultStyle: js.UndefOr[js.Array[String]] = js.native
  val defaultInterpolate: js.UndefOr[String]     = js.native

/** A class definition (classDef) as returned by Mermaid's parser. */
@js.native
trait MermaidClassDefJS extends js.Object:
  val id: String                              = js.native
  val styles: js.UndefOr[js.Array[String]]    = js.native
  val textStyles: js.UndefOr[js.Array[String]] = js.native

/** A subgraph as returned by Mermaid's parser. */
@js.native
trait MermaidSubgraphJS extends js.Object:
  val id: String                          = js.native
  val title: js.UndefOr[String]           = js.native
  val nodes: js.UndefOr[js.Array[String]] = js.native
  val classes: js.UndefOr[js.Array[String]] = js.native

/** Configuration options for Mermaid.initialize() */
object MermaidConfig:
  def apply(
      startOnLoad: Boolean = false,
      securityLevel: String = "loose",
      theme: String = "default",
      suppressErrors: Boolean = false,
      flowchart: js.UndefOr[FlowchartConfig] = js.undefined
  ): js.Object =
    val obj = js.Dynamic.literal(
      startOnLoad = startOnLoad,
      securityLevel = securityLevel,
      theme = theme,
      suppressErrors = suppressErrors
    )
    flowchart.foreach(fc => obj.updateDynamic("flowchart")(fc))
    obj.asInstanceOf[js.Object]

/** Flowchart-specific configuration. */
@js.native
trait FlowchartConfig extends js.Object:
  val htmlLabels: js.UndefOr[Boolean]   = js.native
  val curve: js.UndefOr[String]         = js.native
  val nodeSpacing: js.UndefOr[Int]      = js.native
  val rankSpacing: js.UndefOr[Int]      = js.native
  val useMaxWidth: js.UndefOr[Boolean]  = js.native

object FlowchartConfig:
  def apply(
      htmlLabels: js.UndefOr[Boolean] = js.undefined,
      curve: js.UndefOr[String] = js.undefined,
      nodeSpacing: js.UndefOr[Int] = js.undefined,
      rankSpacing: js.UndefOr[Int] = js.undefined,
      useMaxWidth: js.UndefOr[Boolean] = js.undefined
  ): FlowchartConfig =
    val obj = js.Dynamic.literal()
    htmlLabels.foreach(obj.updateDynamic("htmlLabels")(_))
    curve.foreach(obj.updateDynamic("curve")(_))
    nodeSpacing.foreach(obj.updateDynamic("nodeSpacing")(_))
    rankSpacing.foreach(obj.updateDynamic("rankSpacing")(_))
    useMaxWidth.foreach(obj.updateDynamic("useMaxWidth")(_))
    obj.asInstanceOf[FlowchartConfig]
