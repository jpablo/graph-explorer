package org.jpablo.graphexplorer.viewer.backends.mermaid

import org.jpablo.graphexplorer.viewer.backends.{DiagramBackend, DiagramFormat}
import org.jpablo.graphexplorer.viewer.backends.graphviz.SvgWithPositions
import org.jpablo.graphexplorer.viewer.domUtils.parseSVG
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.scalajs.js
import java.util.concurrent.atomic.AtomicInteger

/** DiagramBackend implementation for Mermaid diagrams.
  *
  * This backend uses the Mermaid.js library to parse and render flowchart diagrams.
  */
class MermaidBackend(using ExecutionContext) extends DiagramBackend:
  // Initialize Mermaid with sensible defaults
  MermaidJS.initialize(
    MermaidConfig(
      startOnLoad = false,
      securityLevel = "loose",
      theme = "default"
    )
  )

  override def format: DiagramFormat = DiagramFormat.Mermaid

  override def textToGraph(text: String): Future[ViewerGraph] =
    parseMermaid(text).map(toViewerGraph)

  override def textToSvg(text: String): Future[SvgWithPositions] =
    val renderId = MermaidBackend.nextRenderId()
    renderMermaid(renderId, text).map { svgString =>
      val svg = parseSVG(svgString)
      // For now, return empty edge positions
      // TODO: Extract edge positions from SVG path elements
      SvgWithPositions(svg, Map.empty)
    }

  /** Parse Mermaid text asynchronously, converting the JS Promise to a Scala Future.
    */
  private def parseMermaid(text: String): Future[MermaidGraph] =
    val promise = Promise[MermaidGraph]()

    MermaidJS.mermaidAPI
      .getDiagramFromText(text)
      .`then`(
        { diagram =>
          try
            val yy        = diagram.parser.yy
            val vertices  = convertVertices(yy.getVertices())
            val edges     = convertEdges(yy.getEdges())
            val subgraphs = convertSubgraphs(yy.getSubGraphs())
            val direction = yy.getDirection().toOption

            promise.success(MermaidGraph(vertices, edges, subgraphs, direction))
          catch case e: Throwable => promise.failure(e)
        },
        (error: Any) =>
          promise.failure(new Exception(s"Mermaid parsing failed: $error"))
      )

    promise.future

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

  def nextRenderId(): String =
    val id = renderCounter.incrementAndGet()
    s"mermaid-render-$id"

  def apply()(using ExecutionContext): MermaidBackend = new MermaidBackend()
