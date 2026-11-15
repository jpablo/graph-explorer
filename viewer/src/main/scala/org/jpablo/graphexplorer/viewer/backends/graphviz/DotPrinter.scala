package org.jpablo.graphexplorer.viewer.backends.graphviz

import org.jpablo.graphexplorer.viewer.backends.{DiagramFormat, Printer}
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph.viewerGraphToText

/** The printer that writes a graph as DOT text. */
object DotPrinter extends Printer:

  override def format: DiagramFormat = DiagramFormat.DOT

  override def graphToText(graph: ViewerGraph, omitInternal: Boolean): String =
    viewerGraphToText(graph, omitInternal)
