package org.jpablo.graphexplorer.gxcore.command

import org.jpablo.graphexplorer.graphviz.Graphviz as ScalaGraphviz
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.{SimpleGraph, toViewerGraph}
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, viewerGraphElementsToText}
import upickle.default.read

import scala.util.control.NonFatal

/** Diagram text ↔ `ViewerGraph`, for headless callers.
  *
  * ## D2.3 is not met here, deliberately
  *
  * D2.3 says command and query implementations use the parser directly, because
  * `renderFormats(text, Seq("dot_json"))` runs a full `dot` layout — 91ms on a
  * 500-edge graph against 8.5ms for parsing alone — and layout is needed to
  * *render*, never to answer "what nodes exist".
  *
  * There is no parser-only path to take. `DotParser.parse` yields graphviz's own
  * AST, and the only DOT→`ViewerGraph` converter in the tree is the one below,
  * which consumes `dot_json` — a layout product. The viewer never noticed
  * because it lays out anyway.
  *
  * So this takes the layout path and says so. The results are correct; a query
  * costs ~91ms on a large graph where it should cost ~9ms. Closing the gap means
  * writing AST→`ViewerGraph` and then proving it *agrees* with this path, or the
  * UI and `gx` would read different graphs from the same file — a V-13-shaped
  * contract, with the corpus available to cross-test against. Worth doing when
  * the 80ms starts to matter; not worth blocking the command tier on.
  */
object DiagramText:

  /** Parse, or say why not.
    *
    * Mermaid is refused rather than mis-parsed. Its converter needs a scan
    * produced by mermaid.js, which is a browser dependency the viewer has and
    * `gx` does not — so on the JVM there is no Mermaid parse at all, and
    * pretending otherwise would silently treat a `.mmd` file as DOT.
    */
  def parse(text: String): Either[String, ViewerGraph] =
    DiagramFormat.detect(text) match
      case DiagramFormat.Mermaid =>
        Left("mermaid diagrams cannot be read headlessly yet (the parser needs a browser)")
      case _ =>
        try
          val result = ScalaGraphviz.renderFormats(text, Seq("dot_json"))
          result.output.get("dot_json") match
            case Some(json) => Right(toViewerGraph(read[SimpleGraph](json)))
            case None =>
              Left(s"could not parse the diagram: ${result.errors.mkString("; ")}")
        catch case NonFatal(e) => Left(s"could not parse the diagram: ${e.getMessage}")

  /** Print a graph back to DOT.
    *
    * `omitInternal` because a round trip must not leak the layout's own
    * bookkeeping (`_gvid` and friends) into the user's file — those are
    * artifacts of how the graph was read, not of what it says.
    */
  def render(graph: ViewerGraph): String =
    viewerGraphElementsToText(graph.elements, omitInternal = true)
