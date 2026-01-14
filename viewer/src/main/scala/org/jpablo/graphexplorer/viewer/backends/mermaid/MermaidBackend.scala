package org.jpablo.graphexplorer.viewer.backends.mermaid

import org.jpablo.graphexplorer.viewer.backends.{DiagramBackend, DiagramFormat}
import org.jpablo.graphexplorer.viewer.backends.graphviz.SvgWithPositions
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.{ArrowPosition, Point}
import org.jpablo.graphexplorer.viewer.components.selection.MermaidSelectionStrategy
import org.jpablo.graphexplorer.viewer.domUtils.parseSVG
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.Arrow

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.scalajs.js
import org.scalajs.dom
import java.util.concurrent.atomic.AtomicInteger

/** DiagramBackend implementation for Mermaid diagrams.
  *
  * This backend uses the Mermaid.js library to parse and render flowchart diagrams.
  */
class MermaidBackend(using ExecutionContext) extends DiagramBackend:
  // Ensure Mermaid is initialized (only happens once)
  MermaidBackend.ensureInitialized()

  override def format: DiagramFormat = DiagramFormat.Mermaid

  override def textToGraph(text: String): Future[ViewerGraph] =
    parseMermaid(text).map(toViewerGraph)

  override def textToSvg(text: String): Future[SvgWithPositions] =
    val renderId = MermaidBackend.nextRenderId()
    renderMermaid(renderId, text).map { svgString =>
      val svg = parseSVG(svgString)
      val edgePositions = extractEdgePositions(svg.ref)
      SvgWithPositions(svg, edgePositions)
    }

  /** Parse Mermaid text asynchronously, converting the JS Promise to a Scala Future.
    * Falls back to SVG-based parsing if getDiagramFromText fails (e.g., during HMR).
    */
  private def parseMermaid(text: String): Future[MermaidGraph] =
    val promise = Promise[MermaidGraph]()
    var completed = false

    dom.console.info(s"[mermaid] getDiagramFromText start len=${text.length}")
    js.timers.setTimeout(2000) {
      if !completed then
        dom.console.warn("[mermaid] getDiagramFromText still pending after 2s")
    }

    MermaidJS.mermaidAPI
      .getDiagramFromText(text)
      .`then`[Unit](
        { diagram =>
          try
            val yy =
              diagram.parser.toOption
                .flatMap(parser => parser.yy.toOption)
                .orElse(diagram.db.toOption)
                .getOrElse(throw new Exception("Mermaid parser database missing"))
            val vertices  = convertVertices(yy.getVertices())
            val edges     = convertEdges(yy.getEdges())
            val subgraphs = convertSubgraphs(yy.getSubGraphs())
            val direction = yy.getDirection().toOption
            // Try getDiagramTitle first, fallback to getAccTitle
            val title = yy.getDiagramTitle().toOption.filter(_.nonEmpty)
              .orElse(yy.getAccTitle().toOption.filter(_.nonEmpty))

            dom.console.info(
              s"[mermaid] parsed vertices=${vertices.size} edges=${edges.size} subgraphs=${subgraphs.size} dir=${direction.getOrElse("")} title=${title.getOrElse("")}"
            )
            completed = true
            promise.success(MermaidGraph(vertices, edges, subgraphs, direction, title))
            ()
          catch case e: Throwable =>
            promise.failure(e)
            ()
        },
        { (error: Any) =>
          completed = true
          val errorStr = error.toString
          // If we get "already registered" error (HMR issue), try fallback via render
          if errorStr.contains("already registered") then
            dom.console.info("[mermaid] getDiagramFromText failed with registration error, trying render fallback")
            parseMermaidViaSvg(text).onComplete {
              case scala.util.Success(graph) => promise.success(graph)
              case scala.util.Failure(e)     => promise.failure(e)
            }
          else
            promise.failure(new Exception(s"Mermaid parsing failed: $error"))
          ()
        }
      )

    promise.future

  /** Fallback: render to SVG and parse the SVG to extract graph structure.
    * Less accurate but works around HMR diagram registration issues.
    */
  private def parseMermaidViaSvg(text: String): Future[MermaidGraph] =
    val renderId = MermaidBackend.nextRenderId()
    renderMermaid(renderId, text).map { svgString =>
      val svg = parseSVG(svgString)
      extractGraphFromSvg(svg.ref)
    }

  /** Extract graph structure from rendered SVG. */
  private def extractGraphFromSvg(svg: dom.svg.SVG): MermaidGraph =
    import MermaidSelectionStrategy.*

    // Extract nodes
    val nodeElements = svg.querySelectorAll(nodeSelector)
    val vertices: Map[String, MermaidVertex] = (0 until nodeElements.length).map { i =>
      val elem = nodeElements.item(i).asInstanceOf[dom.Element]
      val nodeId = extractNodeId(elem)
      val labelElem = elem.querySelector("span.nodeLabel, foreignObject span, text")
      val label = Option(labelElem).map(_.textContent).getOrElse(nodeId.value)
      nodeId.value -> MermaidVertex(
        id = nodeId.value,
        text = label,
        labelType = None,
        domId = Some(elem.id),
        styles = Nil,
        classes = Nil,
        shape = None
      )
    }.toMap

    // Extract edges from arrow IDs (format: "source->target/seq" or "source--target/seq")
    // Use Arrow.fromArrowId to properly parse the ArrowId format and extract source/target
    val edgeElements = svg.querySelectorAll(edgeSelector)
    val edges: List[MermaidEdge] = (0 until edgeElements.length).flatMap { i =>
      val elem = edgeElements.item(i).asInstanceOf[dom.Element]
      val arrowId = extractArrowId(elem)
      Arrow.fromArrowId(arrowId).map { arrow =>
        MermaidEdge(
          start = arrow.source.value,
          end = arrow.target.value,
          edgeType = None,
          text = None,
          labelType = None,
          stroke = None
        )
      }
    }.toList

    dom.console.info(s"[mermaid] SVG fallback parsed vertices=${vertices.size} edges=${edges.size}")
    MermaidGraph(vertices, edges, subgraphs = Nil, direction = None)

  /** Render Mermaid text to SVG asynchronously.
    */
  private def renderMermaid(id: String, text: String): Future[String] =
    val promise = Promise[String]()

    MermaidJS.render(id, text).`then`(
      { renderResult =>
        promise.success(renderResult.svg)
      },
      (error: Any) =>
        promise.failure(new Exception(s"Mermaid rendering failed: $error"))
    )

    promise.future

  private def extractEdgePositions(svg: dom.svg.SVG): Map[String, ArrowPosition] =
    val nodeList = svg.querySelectorAll(MermaidSelectionStrategy.edgeSelector)
    val positions = scala.collection.mutable.Map[String, ArrowPosition]()

    for i <- 0 until nodeList.length do
      val elem = nodeList.item(i).asInstanceOf[dom.Element]
      val pathOpt = elem match
        case path: dom.svg.Path => Some(path)
        case _ =>
          Option(elem.querySelector("path")).collect { case p: dom.svg.Path => p }

      pathOpt.foreach { path =>
        try
          val total = path.getTotalLength()
          val start = path.getPointAtLength(0)
          val end = path.getPointAtLength(total)
          val startPoint = Point(start.x, -start.y)
          val endPoint = Point(end.x, -end.y)
          // For Mermaid, the path element itself has the LS-/LE- classes needed for ID extraction.
          // Using the parent (edgePaths group) doesn't work because it lacks those classes.
          val arrowId = MermaidSelectionStrategy.extractArrowId(path).value
          positions.update(arrowId, ArrowPosition(startPoint, endPoint, controlPoints = Nil))
        catch
          case _: Throwable =>
            ()
      }

    positions.toMap

  /** Convert Mermaid JS vertices to Scala model. */
  private def convertVertices(jsVertices: js.Dictionary[MermaidVertexJS]): Map[String, MermaidVertex] =
    jsVertices.map { case (id, v) =>
      id -> MermaidVertex(
        id = v.id,
        text = v.text,
        labelType = v.labelType.toOption,
        domId = v.domId.toOption,
        styles = v.styles.toOption.map(_.toList).getOrElse(Nil),
        classes = v.classes.toOption.map(_.toList).getOrElse(Nil),
        shape = v.`type`.toOption
      )
    }.toMap

  /** Convert Mermaid JS edges to Scala model. */
  private def convertEdges(jsEdges: js.Array[MermaidEdgeJS]): List[MermaidEdge] =
    jsEdges.map { e =>
      MermaidEdge(
        start = e.start,
        end = e.end,
        edgeType = e.`type`.toOption,
        text = e.text.toOption.filter(_.nonEmpty),
        labelType = e.labelType.toOption,
        stroke = e.stroke.toOption
      )
    }.toList

  /** Convert Mermaid JS subgraphs to Scala model. */
  private def convertSubgraphs(jsSubgraphs: js.Array[MermaidSubgraphJS]): List[MermaidSubgraph] =
    jsSubgraphs.map { s =>
      MermaidSubgraph(
        id = s.id,
        title = s.title.toOption,
        nodes = s.nodes.toOption.map(_.toList).getOrElse(Nil)
      )
    }.toList

object MermaidBackend:
  private val renderCounter = new AtomicInteger(0)

  // Check initialization flag from window object to survive HMR
  private val windowDyn = dom.window.asInstanceOf[js.Dynamic]

  private def isInitialized: Boolean =
    !js.isUndefined(windowDyn.__mermaidInitialized) &&
      windowDyn.__mermaidInitialized.asInstanceOf[Boolean]

  private def setInitialized(): Unit =
    windowDyn.__mermaidInitialized = true

  /** Initialize Mermaid.js only once, regardless of HMR reloads or multiple MermaidBackend instances. */
  private[mermaid] def ensureInitialized(): Unit =
    if !isInitialized then
      MermaidJS.initialize(
        MermaidConfig(
          startOnLoad = false,
          securityLevel = "loose",
          theme = "default",
          suppressErrors = true,
          flowchart = FlowchartConfig(useMaxWidth = false)
        )
      )
      setInitialized()
      dom.console.info("[mermaid] Mermaid.js initialized")

  def nextRenderId(): String =
    val id = renderCounter.incrementAndGet()
    s"mermaid-render-$id"

  def apply()(using ExecutionContext): MermaidBackend = new MermaidBackend()
