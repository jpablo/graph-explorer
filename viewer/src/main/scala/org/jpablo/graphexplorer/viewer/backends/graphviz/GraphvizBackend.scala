package org.jpablo.graphexplorer.viewer.backends.graphviz

import org.jpablo.graphexplorer.viewer.backends.{DiagramBackend, DiagramFormat}
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph
import org.jpablo.graphexplorer.viewer.formats.dot.DotText
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph

import scala.util.Try

/** DiagramBackend implementation for DOT/Graphviz diagrams.
  *
  * This wraps the existing Graphviz class to provide the DiagramBackend interface.
  */
class GraphvizBackend(graphviz: Graphviz) extends DiagramBackend:

  override def format: DiagramFormat = DiagramFormat.DOT

  override def textToGraph(text: String): Try[ViewerGraph] =
    graphviz.textToSimpleGraph(text).map(simplegraph.toViewerGraph)

  override def textToSvg(text: String): Try[SvgWithPositions] =
    graphviz.textToSvg(DotText(text))
