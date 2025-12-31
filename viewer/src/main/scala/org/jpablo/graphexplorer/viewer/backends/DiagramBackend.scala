package org.jpablo.graphexplorer.viewer.backends

import org.jpablo.graphexplorer.viewer.backends.graphviz.SvgWithPositions
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph

import scala.concurrent.Future

/** A backend that can parse diagram text and render it to SVG.
  *
  * This trait abstracts over different diagram formats (DOT/Graphviz, Mermaid, etc.) to provide a unified interface for
  * the application.
  *
  * Methods return Futures to support both synchronous backends (like Graphviz) and asynchronous backends (like Mermaid
  * which uses Promises).
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
