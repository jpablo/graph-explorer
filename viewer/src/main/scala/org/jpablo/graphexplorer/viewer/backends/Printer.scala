package org.jpablo.graphexplorer.viewer.backends

import org.jpablo.graphexplorer.viewer.graph.ViewerGraph

/** The part that writes a graph as text in one language (ADR 0002).
  *
  * A printer has two callers. A canvas edit uses the printer of the language of the source, to
  * write the graph back. A [[Renderer]] draws the text that the printer of its language makes
  * from the visible graph.
  */
trait Printer:
  /** The language of the text that this printer writes. */
  def format: DiagramFormat

  /** Serialize a ViewerGraph into text (the inverse of [[Extractor.textToGraph]]).
    *
    * @param graph
    *   The graph to serialize
    * @param omitInternal
    *   Drop internal-only attributes (e.g. generated ids). Printers without such attributes ignore it.
    */
  def graphToText(graph: ViewerGraph, omitInternal: Boolean): String
