package org.jpablo.graphexplorer.viewer.backends.graphviz

import com.raquo.airstream.core.Signal
import org.jpablo.graphexplorer.gxcore.layout.FreeLayout
import org.jpablo.graphexplorer.gxcore.model.{DiagramLayout, LayoutPoint}
import org.jpablo.graphexplorer.viewer.backends.{DiagramFormat, DiagramRenderInputs, LocalImages, RenderState, Renderer}
import org.jpablo.graphexplorer.viewer.components.selection.{GraphvizSelectionStrategy, SelectableElementStrategy}
import org.jpablo.graphexplorer.viewer.formats.dot.DotText
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.ArrowId

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

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
    // In the manual mode, a stored position pins its node, and neato places each other node (the free layout,
    // ADR 0004). The pins go only into this text. A layout that is not manual gives the same graph, so an automatic
    // diagram draws as before.
    // An arrow with bend points goes through them (part 4). neato honours the `pos` of an arrow only with its `-n2`
    // option, and viz-js does not give that option. Thus the renderer draws the path of such an arrow again in the SVG
    // of neato (see BendRender).
    // neato draws each group as a tight box around its nodes, with no room for the label. In a free layout, each box
    // gets the room for its label, as in dot (see ClusterLabelRoom). The nodes do not move.
    inputs.visibleGraph
      .combineWith(inputs.localImages.distinct, inputs.layout.distinct, inputs.bends.distinct)
      .map: (graph, images, layout, bends) =>
        GraphvizRenderer.drawGraph(graphviz, graph, layout, bends, images) match
          case Success(drawn) => RenderState.Drawn(drawn)
          case Failure(error) => RenderState.NoDrawing(Option(error.getMessage))

object GraphvizRenderer:

  /** Draw `graph` with Graphviz in its layout. The view of a diagram and the thumbnail of the library both use this
    * function, so that the two show the same drawing (ADR 0004).
    *
    * In the manual mode, a stored position pins its node, and neato places each other node. Each arrow of `bends`
    * goes through its bend points (see [[BendRender]]), and each group box gets the room for its label (see
    * [[ClusterLabelRoom]]). A layout that is not manual gives the drawing of the engine of the graph.
    *
    * @param bends the bend points of each arrow: `FreeLayout.bendsByArrow` of the full graph and the layout.
    * @param images the images next to the file of the diagram. The DOT text has their URLs.
    */
  def drawGraph(
      graphviz: Graphviz,
      graph:    ViewerGraph,
      layout:   DiagramLayout,
      bends:    Map[ArrowId, Vector[LayoutPoint]],
      images:   LocalImages = LocalImages.empty
  ): Try[SvgWithPositions] =
    val drawn = FreeLayout.renderGraph(images.graph(graph), layout)
    val dot   = DotText(DotPrinter.graphToText(drawn, omitInternal = false))
    graphviz.textToSvg(dot, images.table).map: svg =>
      val rerouted = BendRender.applyTo(svg, graph, layout, bends)
      if layout.isManual then ClusterLabelRoom.applyTo(rerouted) else rerouted
