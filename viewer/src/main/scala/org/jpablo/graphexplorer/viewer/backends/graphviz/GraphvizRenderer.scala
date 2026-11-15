package org.jpablo.graphexplorer.viewer.backends.graphviz

import com.raquo.airstream.core.Signal
import org.jpablo.graphexplorer.viewer.backends.{DiagramFormat, DiagramRenderInputs, Renderer}
import org.jpablo.graphexplorer.viewer.components.selection.{GraphvizSelectionStrategy, SelectableElementStrategy}
import org.jpablo.graphexplorer.viewer.formats.dot.DotText

import scala.concurrent.Future

/** The renderer that draws DOT text with Graphviz.
  *
  * This wraps the existing Graphviz class to provide the Renderer interface.
  * Since Graphviz operations are synchronous, results are wrapped in Future.fromTry.
  */
class GraphvizRenderer(graphviz: Graphviz) extends Renderer:

  override def format: DiagramFormat = DiagramFormat.DOT

  override def textToSvg(text: String): Future[SvgWithPositions] =
    Future.fromTry(graphviz.textToSvg(DotText(text)))

  override def selectionStrategy: SelectableElementStrategy = GraphvizSelectionStrategy

  override def render(inputs: DiagramRenderInputs): Signal[Option[SvgWithPositions]] =
    // Graphviz is synchronous, so render the visible graph's DOT directly (via map) rather than through
    // a Future. This keeps downstream reads synchronous with the graph edit that triggered them.
    inputs.visibleText.map(dot => graphviz.textToSvg(DotText(dot)).toOption)
