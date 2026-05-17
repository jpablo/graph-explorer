package org.jpablo.graphexplorer.viewer.backends.graphviz

import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.{SimpleGraph, ArrowPosition}
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.{Viz, VizJS}
import org.jpablo.graphexplorer.viewer.domUtils.parseSVG
import org.jpablo.graphexplorer.viewer.formats.dot.DotText
import org.jpablo.graphexplorer.graphviz.Graphviz as ScalaGraphviz
import upickle.default.*

import scala.concurrent.Future

import scala.scalajs.js
import scala.util.Try

case class SvgWithPositions(
    svg:           ReactiveSvgElement[dom.svg.SVG],
    edgePositions: Map[String, ArrowPosition]
)

/** Backend-neutral render result (the slice both engines expose). */
private case class RenderOutputs(status: String, output: Map[String, String], errors: String)

class Graphviz(viz: Viz):

  /** M8 seam: route through the pure-Scala graphviz backend when the
    * `gx.graphvizEngine=scala` feature flag is set, else viz-js (default
    * + fallback + oracle). Downstream consumption (`read[SimpleGraph]` /
    * `getEdgePos` / `parseSVG`) is identical for both engines.
    */
  private def renderOutputs(dot: String, formats: Seq[String]): RenderOutputs =
    if Graphviz.useScalaBackend then
      val r = ScalaGraphviz.renderFormats(dot, formats)
      RenderOutputs(r.status, r.output, r.errors.map(_.message).mkString("; "))
    else
      val r = viz.renderFormats(dot, js.Array(formats*))
      RenderOutputs(r.status, r.output.toMap, r.errors.toSeq.map(_.message).mkString("; "))

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

  /** Feature flag: `localStorage["gx.graphvizEngine"] == "scala"` opts into
    * the pure-Scala backend. Absent/other ⇒ viz-js (unchanged default). */
  def useScalaBackend: Boolean =
    Try(dom.window.localStorage.getItem("gx.graphvizEngine") == "scala").getOrElse(false)

  def build(): Future[Graphviz] =
    VizJS.instance()
      .`then`(viz => Graphviz(viz))
      .toFuture
