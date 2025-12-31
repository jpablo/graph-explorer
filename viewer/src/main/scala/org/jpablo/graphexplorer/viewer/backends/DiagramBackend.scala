package org.jpablo.graphexplorer.viewer.backends

import org.jpablo.graphexplorer.viewer.backends.graphviz.SvgWithPositions
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph

import scala.util.Try

/** A backend that can parse diagram text and render it to SVG.
  *
  * This trait abstracts over different diagram formats (DOT/Graphviz, Mermaid, etc.) to provide a unified interface for
  * the application.
  */
trait DiagramBackend:
  /** The format this backend handles. */
  def format: DiagramFormat

  /** Parse diagram text into a ViewerGraph.
    *
    * @param text
    *   The diagram source text
    * @return
    *   A Try containing the parsed ViewerGraph, or an error
    */
  def textToGraph(text: String): Try[ViewerGraph]

  /** Render diagram text to SVG with position data.
    *
    * @param text
    *   The diagram source text
    * @return
    *   A Try containing the SVG and edge positions, or an error
    */
  def textToSvg(text: String): Try[SvgWithPositions]
