package org.jpablo.graphexplorer.viewer.backends.mermaid

import org.jpablo.graphexplorer.viewer.backends.{DiagramFormat, Printer}
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph

/** The printer that writes a graph as Mermaid text: a flowchart, or a state diagram
  * (`stateDiagram-v2`) when the graph is one.
  */
object MermaidPrinter extends Printer:

  override def format: DiagramFormat = DiagramFormat.Mermaid

  override def graphToText(graph: ViewerGraph, omitInternal: Boolean): String =
    // Mermaid has no internal-only attributes, so `omitInternal` does not apply.
    viewerGraphToMermaidText(graph)
