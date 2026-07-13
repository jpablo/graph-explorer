package org.jpablo.graphexplorer.viewer.backends.graphviz

import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.{SimpleGraph, ArrowPosition}
import org.jpablo.graphexplorer.viewer.domUtils.parseSVG
import org.jpablo.graphexplorer.viewer.formats.dot.DotText
import org.jpablo.graphexplorer.graphviz.Graphviz as ScalaGraphviz
import upickle.default.*

import scala.concurrent.Future
import scala.util.Try

case class SvgWithPositions(
    svg:           ReactiveSvgElement[dom.svg.SVG],
    edgePositions: Map[String, ArrowPosition]
)

/** Backend-neutral render result (the slice both engines expose). */
private case class RenderOutputs(status: String, output: Map[String, String], errors: String)

class Graphviz():

  /** The pure-Scala graphviz backend is the sole rendering engine (viz-js was
    * dropped once the shape catalog was byte-exact — see graphviz PORT.md §4;
    * `CorpusByteExactSpec`/`DifferentialSpec`/`ShapeCatalogSpec` are the guard).
    * A hard failure (thrown exception, or `status != "success"`) surfaces to the
    * caller (`textToSvg`/`textToSimpleGraph` are wrapped in `Try`) instead of
    * being masked by a fallback. Downstream consumption (`read[SimpleGraph]` /
    * `getEdgePos` / `parseSVG`) is unchanged. */
  private def renderOutputs(dot: String, formats: Seq[String]): RenderOutputs =
    val r = ScalaGraphviz.renderFormats(dot, formats)
    RenderOutputs(r.status, r.output, r.errors.map(_.message).mkString("; "))

  /** Used to parse the DOT text in CodeMirror and render it to a graph.
    *
    * This is the first step in the rendering process.
    */
  def textToSimpleGraph(dotText: String): Try[SimpleGraph] =
    Try {
      val result  = renderOutputs(sanitizeText(dotText), Seq("dot_json"))
      val dotJson = result.output("dot_json")
      read[SimpleGraph](dotJson)
    }

  // TODO: investigate why is this needed
  private def sanitizeText(text: String): String =
    // Remove leading newlines in labels:
    // label="\na\nb" -> label="a\nb"
    // label = "\na\nb" -> label = "a\nb"
    text.replaceAll("""(label\s*=\s*")\\n+""", "$1")

  /** The last step in the rendering process.
    */
  def textToSvg(dot: DotText): Try[SvgWithPositions] =
    Try {
      // all formats js.Array("canon", "dot", "xdot", "json0", "json", "svg", "dot_json")
      val result = renderOutputs(dot.value, Seq("svg", "json0"))
      if result.status == "success" then
        val result_svg   = result.output("svg")
        val result_json0 = result.output("json0")
        val graph        = read[SimpleGraph](result_json0)
        val edgePos      = simplegraph.getEdgePos(graph)
        SvgWithPositions(parseSVG(result_svg), edgePos)
      else
        dom.console.group("Graphviz.renderToSvg")
        dom.console.error(dot.value)
        dom.console.error(result.errors)
        dom.console.groupEnd()
        throw new Exception(s"Graphviz rendering failed: ${result.status} - ${result.errors}")
    }

object Graphviz:

  /** The pure-Scala engine needs no async initialization (viz-js's WASM load is
    * gone), so this resolves immediately; the `Future` signature is retained so
    * the call site (`Viewer`) is unchanged. */
  def build(): Future[Graphviz] = Future.successful(Graphviz())
