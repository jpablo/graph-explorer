package org.jpablo.graphexplorer.viewer.backends

import org.jpablo.graphexplorer.viewer.backends.graphviz.SvgWithPositions
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElementStrategy
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph

import scala.concurrent.Future

/** A backend that can parse diagram text, render it to SVG, and serialize a graph back to text.
  *
  * This trait abstracts over different diagram formats (DOT/Graphviz, Mermaid, etc.) to provide a unified interface for
  * the application. It is the single seam through which format-specific behavior is injected, so components such as
  * `InternalPhases` can depend on this abstraction instead of concrete backends.
  *
  * Parsing/rendering methods return Futures to support both synchronous backends (like Graphviz) and asynchronous
  * backends (like Mermaid which uses Promises).
  */
trait DiagramBackend:
  /** The format this backend handles. */
  def format: DiagramFormat

  /** Parse diagram text into a ViewerGraph.
    *
    * @param text
    *   The diagram source text
    * @return
    *   A Future containing the parsed ViewerGraph
    */
  def textToGraph(text: String): Future[ViewerGraph]

  /** Render diagram text to SVG with position data.
    *
    * @param text
    *   The diagram source text
    * @return
    *   A Future containing the SVG and edge positions
    */
  def textToSvg(text: String): Future[SvgWithPositions]

  /** Serialize a ViewerGraph back into this backend's diagram text (the inverse of [[textToGraph]]).
    *
    * @param graph
    *   The graph to serialize
    * @param omitInternal
    *   Drop internal-only attributes (e.g. generated ids). Backends without such attributes ignore it.
    */
  def graphToText(graph: ViewerGraph, omitInternal: Boolean): String

  /** Strategy for extracting element ids from the SVGs this backend produces. */
  def selectionStrategy: SelectableElementStrategy
