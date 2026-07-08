package org.jpablo.graphexplorer.viewer.backends.graphviz

import com.raquo.airstream.core.Signal
import org.jpablo.graphexplorer.viewer.backends.{DiagramBackend, DiagramFormat, DiagramLanguageInfo, DiagramRenderInputs}
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph
import org.jpablo.graphexplorer.viewer.components.selection.{GraphvizSelectionStrategy, SelectableElementStrategy}
import org.jpablo.graphexplorer.viewer.formats.dot.DotText
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph.viewerGraphToText

import scala.concurrent.Future

/** DiagramBackend implementation for DOT/Graphviz diagrams.
  *
  * This wraps the existing Graphviz class to provide the DiagramBackend interface.
  * Since Graphviz operations are synchronous, results are wrapped in Future.fromTry.
  */
class GraphvizBackend(graphviz: Graphviz) extends DiagramBackend:

  override def format: DiagramFormat = DiagramFormat.DOT

  override def info: DiagramLanguageInfo = DiagramLanguageInfo(
    selectorLabel = "Graphviz (DOT)",
    editorPlaceholder = "DOT source",
    documentationUrl = "https://www.graphviz.org/documentation/",
    documentationTitle = "Visit the Graphviz documentation for more information"
  )

  override def textToGraph(text: String): Future[ViewerGraph] =
    Future.fromTry(graphviz.textToSimpleGraph(text).map(simplegraph.toViewerGraph))

  override def textToSvg(text: String): Future[SvgWithPositions] =
    Future.fromTry(graphviz.textToSvg(DotText(text)))

  override def graphToText(graph: ViewerGraph, omitInternal: Boolean): String =
    viewerGraphToText(graph, omitInternal)

  override def selectionStrategy: SelectableElementStrategy = GraphvizSelectionStrategy

  override def render(inputs: DiagramRenderInputs): Signal[Option[SvgWithPositions]] =
    // Graphviz is synchronous, so render the visible graph's DOT directly (via map) rather than through
    // a Future. This keeps downstream reads synchronous with the graph edit that triggered them.
    inputs.visibleText.map(dot => graphviz.textToSvg(DotText(dot)).toOption)
