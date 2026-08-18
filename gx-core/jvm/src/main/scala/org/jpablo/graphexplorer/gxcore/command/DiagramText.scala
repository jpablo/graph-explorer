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
  * ## D2.3 is met (P8)
  *
  * It turned out not to need a second DOT reader at all. `dot_json` is
  * *structure only* apart from one field: `bb`, the bounding box, which is the
  * sole reason a query was paying for a full layout. `Graphviz.structureJson`
  * emits the same document with a degenerate box, and `SimpleGraph` has no
  * `bb` field — `ExtraAttrs.layoutOnlyKeys` drops the key before capture — so
  * no reader downstream can tell the difference.
  *
  * Everything that was feared hard was already done and oracle-verified:
  * `AttrResolver` handles scoping, node dedup, edge chains, ports and clusters,
  * and `Output.doc` assigns `_gvid` in cgraph's `agfstout` order, which is what
  * `Arrow.seq` — and therefore every `ArrowId` — depends on.
  *
  * `StructureAgreementSpec` sweeps all 168 corpus files and asserts both paths
  * read the same `ViewerGraph`.
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
        // P8: the STRUCTURE, without a layout. `parse -> resolve` is shared
        // with the layout path verbatim — same scoping, same node dedup, same
        // cgraph edge ordering — so this is not a second reading of DOT.
        // `StructureAgreementSpec` sweeps all 168 corpus files to say so out
        // loud rather than on the strength of having read the code.
        try
          ScalaGraphviz.structureJson(text) match
            case Right(json) => Right(toViewerGraph(read[SimpleGraph](json)))
            case Left(err)   => Left(s"could not parse the diagram: $err")
        catch case NonFatal(e) => Left(s"could not parse the diagram: ${e.getMessage}")

  /** Print a graph back to DOT.
    *
    * `omitInternal` because a round trip must not leak the layout's own
    * bookkeeping (`_gvid` and friends) into the user's file — those are
    * artifacts of how the graph was read, not of what it says.
    */
  def render(graph: ViewerGraph): String =
    viewerGraphElementsToText(graph.elements, omitInternal = true)
