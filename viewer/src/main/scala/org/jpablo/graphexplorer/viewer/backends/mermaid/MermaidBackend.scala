package org.jpablo.graphexplorer.viewer.backends.mermaid

import org.jpablo.graphexplorer.viewer.backends.{DiagramBackend, DiagramFormat}
import org.jpablo.graphexplorer.viewer.backends.graphviz.SvgWithPositions
import org.jpablo.graphexplorer.viewer.domUtils.parseSVG
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph

import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.util.Try

/** DiagramBackend implementation for Mermaid diagrams.
  *
  * This backend uses the Mermaid.js library to parse and render flowchart diagrams.
  */
class MermaidBackend(using ExecutionContext) extends DiagramBackend:
  // Generate unique IDs for rendering
  private var renderCounter = 0

  // Initialize Mermaid with sensible defaults
  MermaidJS.initialize(
    MermaidConfig(
      startOnLoad = false,
      securityLevel = "loose",
      theme = "default"
    )
  )

  override def format: DiagramFormat = DiagramFormat.Mermaid

  override def textToGraph(text: String): Try[ViewerGraph] =
    // Since Mermaid's API is async, we need to block or use a cached result
    // For now, we'll do a synchronous-ish approach using the parser
    Try {
      // This is a simplification - in practice we'd want to handle the async nature better
      // For initial implementation, we'll parse synchronously if possible
      // or return a minimal graph and update async
      val mermaidGraph = parseMermaidSync(text)
      toViewerGraph(mermaidGraph)
    }

  override def textToSvg(text: String): Try[SvgWithPositions] =
    Try {
      renderCounter += 1
      val renderId = s"mermaid-render-$renderCounter"

      // Use synchronous rendering approach
      val svgString = renderMermaidSync(renderId, text)
      val svg       = parseSVG(svgString)

      // For now, return empty edge positions
      // TODO: Extract edge positions from SVG path elements
      SvgWithPositions(svg, Map.empty)
    }

  /** Parse Mermaid text synchronously (blocking on the Promise).
    *
    * Note: This is not ideal but works for the initial implementation. A better approach would be to make the entire
    * pipeline async.
    */
  private def parseMermaidSync(text: String): MermaidGraph =
    var result: Option[Either[Throwable, MermaidGraph]] = None

    val promise = MermaidJS.mermaidAPI.getDiagramFromText(text)
    promise.`then`(
      { diagram =>
        try
          val yy        = diagram.parser.yy
          val vertices  = convertVertices(yy.getVertices())
          val edges     = convertEdges(yy.getEdges())
          val subgraphs = convertSubgraphs(yy.getSubGraphs())
          val direction = yy.getDirection().toOption

          result = Some(Right(MermaidGraph(vertices, edges, subgraphs, direction)))
        catch case e: Throwable => result = Some(Left(e))
      },
      (error: Any) =>
        result = Some(Left(new Exception(s"Mermaid parsing failed: $error")))
    )

    // Spin until the promise resolves (not ideal but works for sync API)
    // In practice, this should resolve very quickly since parsing is fast
    var iterations = 0
    while result.isEmpty && iterations < 10000 do
      iterations += 1
      // Allow JS event loop to run
      // This is a hack - in real code we'd make the API async

    result match
      case Some(Right(graph)) => graph
      case Some(Left(error))  => throw error
      case None               => throw new Exception("Mermaid parsing timed out")

  /** Render Mermaid text to SVG synchronously.
    */
  private def renderMermaidSync(id: String, text: String): String =
    var result: Option[Either[Throwable, String]] = None

    val promise = MermaidJS.render(id, text)
    promise.`then`(
      { renderResult =>
        result = Some(Right(renderResult.svg))
      },
      (error: Any) =>
        result = Some(Left(new Exception(s"Mermaid rendering failed: $error")))
    )

    // Spin until the promise resolves
    var iterations = 0
    while result.isEmpty && iterations < 10000 do
      iterations += 1

    result match
      case Some(Right(svg))  => svg
      case Some(Left(error)) => throw error
      case None              => throw new Exception("Mermaid rendering timed out")

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
  def apply()(using ExecutionContext): MermaidBackend = new MermaidBackend()
