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

  /** M8 seam: route through the pure-Scala graphviz backend, with viz-js as
    * an automatic safety net + oracle. Modes (`gx.graphvizEngine`):
    *   - `"vizjs"`      ⇒ viz-js only (escape hatch / A-B against the oracle);
    *   - `"scala"`      ⇒ Scala only, no fallback (strict testing — surfaces
    *                       any Scala failure instead of hiding it);
    *   - unset / other  ⇒ **Scala-first**: try Scala, and on a hard failure
    *                       (status != success, or a thrown exception) fall
    *                       back to viz-js, logging the DOT + reason so any
    *                       real-diagram gap the corpus misses is visible.
    *
    * Downstream consumption (`read[SimpleGraph]` / `getEdgePos` / `parseSVG`)
    * is identical for both engines. Only *hard* failures fall back — a
    * valid-but-divergent Scala layout is not caught here (the byte-exact
    * corpus + DifferentialSpec are the guard against that).
    */
  private def renderViz(dot: String, formats: Seq[String]): RenderOutputs =
    val r = viz.renderFormats(dot, js.Array(formats*))
    RenderOutputs(r.status, r.output.toMap, r.errors.toSeq.map(_.message).mkString("; "))

  private def renderScala(dot: String, formats: Seq[String]): RenderOutputs =
    val r = ScalaGraphviz.renderFormats(dot, formats)
    RenderOutputs(r.status, r.output, r.errors.map(_.message).mkString("; "))

  private def renderOutputs(dot: String, formats: Seq[String]): RenderOutputs =
    Graphviz.engineMode match
      case Graphviz.EngineMode.VizJsOnly => renderViz(dot, formats)
      case Graphviz.EngineMode.ScalaOnly => renderScala(dot, formats)
      case Graphviz.EngineMode.ScalaFirst =>
        Try(renderScala(dot, formats)) match
          case scala.util.Success(r) if r.status == "success" => r
          case attempt =>
            val reason = attempt match
              case scala.util.Success(r) => s"status=${r.status}: ${r.errors}"
              case scala.util.Failure(e) => s"threw: ${e.getMessage}"
            dom.console.warn(s"[graphviz] Scala backend fell back to viz-js ($reason)")
            renderViz(dot, formats)

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

  /** Engine selection (`localStorage["gx.graphvizEngine"]`). The DEFAULT
    * (unset) is **Scala-first with viz-js fallback** — the pure-Scala engine
    * is byte-exact vs viz-js across the whole corpus (see graphviz
    * CorpusByteExactSpec/DifferentialSpec), and viz-js stays loaded as the
    * automatic safety net + oracle. Set `"vizjs"` to force the old engine,
    * `"scala"` to force Scala with no fallback (testing). */
  enum EngineMode derives CanEqual:
    case ScalaFirst, ScalaOnly, VizJsOnly

  def engineMode: EngineMode =
    Try(dom.window.localStorage.getItem("gx.graphvizEngine")).toOption match
      case Some("vizjs") => EngineMode.VizJsOnly
      case Some("scala") => EngineMode.ScalaOnly
      case _             => EngineMode.ScalaFirst

  def build(): Future[Graphviz] =
    VizJS.instance()
      .`then`(viz => Graphviz(viz))
      .toFuture
