package org.jpablo.graphexplorer.viewer.backends.graphviz

import com.raquo.airstream.core.Signal
import org.jpablo.graphexplorer.viewer.backends.{DiagramFormat, DiagramRenderInputs, RenderState, Renderer}
import org.jpablo.graphexplorer.viewer.components.selection.{GraphvizSelectionStrategy, SelectableElementStrategy}
import org.jpablo.graphexplorer.viewer.formats.dot.DotText

import scala.concurrent.Future
import scala.util.{Failure, Success}

/** The renderer that draws DOT text with Graphviz.
  *
  * This wraps the existing Graphviz class to provide the Renderer interface.
  * Since Graphviz operations are synchronous, results are wrapped in Future.fromTry.
  */
class GraphvizRenderer(graphviz: Graphviz) extends Renderer:

  override def id: String = "graphviz"

  override def label: String = "Graphviz"

  override def format: DiagramFormat = DiagramFormat.DOT

  override def textToSvg(text: String): Future[SvgWithPositions] =
    Future.fromTry(graphviz.textToSvg(DotText(text)))

  override def selectionStrategy: SelectableElementStrategy = GraphvizSelectionStrategy

  override def render(inputs: DiagramRenderInputs): Signal[RenderState] =
    // Graphviz is synchronous, so render the visible graph's DOT directly (via map) rather than through
    // a Future. This keeps downstream reads synchronous with the graph edit that triggered them.
    // The DOT text keeps each id of the graph, so the SVG has the ids of the graph.
    // A render is never pending. A failed render gives no drawing, and the message of the error is the cause.
    // An image with a path relative to the diagram is drawn from its URL, with the size from its header (see
    // LocalImages). The DOT text for Graphviz has the URL, and the source keeps the path.
    inputs.visibleGraph.combineWith(inputs.localImages.distinct).map: (graph, images) =>
      val dot = DotText(DotPrinter.graphToText(images.graph(graph), omitInternal = false))
      graphviz.textToSvg(dot, images.table) match
        case Success(drawn) => RenderState.Drawn(drawn)
        case Failure(error) => RenderState.NoDrawing(Option(error.getMessage))
