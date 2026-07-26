package org.jpablo.graphexplorer.viewer.backends.graphviz

import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.{SimpleGraph, ArrowPosition}
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.{Viz, VizJS}
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.domUtils.{parseSVG, querySelectorAllT}
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

  /** Engine-aware dispatch. The pure-Scala backend implements **only** the
    * `dot` layout engine (byte-exact vs viz-js — see graphviz PORT.md;
    * `CorpusByteExactSpec`/`DifferentialSpec`/`ShapeCatalogSpec` are the guard),
    * so it renders every `dot`/unset graph. The other Graphviz engines
    * (`neato`/`fdp`/`sfdp`/`twopi`/`circo`/`osage`/`patchwork`) are **not**
    * ported and are delegated to viz-js, which reads the `layout` attribute
    * itself. The engine is decided from the graph `layout` attribute
    * ([[Graphviz.usesDotEngine]]).
    *
    * The `dot` path has no viz-js fallback on purpose: a hard failure there is a
    * port bug we want visible (it surfaces to the `Try`-wrapped callers), not
    * silently masked. Downstream consumption (`read[SimpleGraph]` /
    * `getEdgePos` / `parseSVG`) is identical for both engines. */
  private def renderScala(dot: String, formats: Seq[String]): RenderOutputs =
    val r = ScalaGraphviz.renderFormats(dot, formats)
    RenderOutputs(r.status, r.output, r.errors.map(_.message).mkString("; "))

  private def renderViz(dot: String, formats: Seq[String]): RenderOutputs =
    val r = viz.renderFormats(dot, js.Array(formats*))
    RenderOutputs(r.status, r.output.toMap, r.errors.toSeq.map(_.message).mkString("; "))

  private def renderOutputs(dot: String, formats: Seq[String]): RenderOutputs =
    if Graphviz.usesDotEngine(dot) then renderScala(dot, formats)
    else renderViz(dot, formats) // non-dot layout engine → viz-js (not ported)

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

  /** Thumbnail fast path: ONE render pass requesting only `svg`. Unlike
    * [[textToSvg]] it skips the json0 render, its JSON parse, and the
    * edge-position extraction — none of which a static thumbnail reads.
    */
  def textToSvgOnly(dot: DotText): Try[dom.svg.SVG] =
    Try {
      val result = renderOutputs(sanitizeText(dot.value), Seq("svg"))
      if result.status == "success" then parseSVG(result.output("svg")).ref
      else throw new Exception(s"Graphviz rendering failed: ${result.status} - ${result.errors}")
    }

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
        val svg          = parseSVG(result_svg)
        Graphviz.addEdgeHitAreas(svg.ref)
        SvgWithPositions(svg, edgePos)
      else
        dom.console.group("Graphviz.renderToSvg")
        dom.console.error(dot.value)
        dom.console.error(result.errors)
        dom.console.groupEnd()
        throw new Exception(s"Graphviz rendering failed: ${result.status} - ${result.errors}")
    }

object Graphviz:

  /** Graphviz edges are thin splines — a hard click target, same as Mermaid's
    * (see MermaidBackend.addEdgeHitAreas). Clone each edge group's spline as an
    * invisible ~14 screen px path so near-misses still select the edge. Unlike
    * Mermaid, no id plumbing is needed: clicks resolve through
    * `closest("g.edge")`, and the clone lives INSIDE that group. It is appended
    * LAST so `querySelector("path")` — the endpoint-drag preview and the
    * extractArrowId fallback — keeps resolving to the rendered spline. Labels
    * need nothing either: Graphviz emits them as SVG `<text>` inside the group,
    * so they already resolve (no foreignObject namespace trap here).
    */
  private[graphviz] def addEdgeHitAreas(svg: dom.svg.SVG): Unit =
    svg.querySelectorAllT[dom.Element]("g.edge > path").foreach { p =>
      p.parentNode.appendChild(SelectableElement.hitHaloClone(p))
    }

  /** True when the graph uses the `dot` engine — the only one the Scala port
    * implements. `dot`/unset → the port, every other engine → viz-js. The
    * predicate lives in the shared graphviz module ([[EngineRouting]]) so the
    * JVM example gate routes with EXACTLY the code the viewer ships. */
  def usesDotEngine(dot: String): Boolean =
    org.jpablo.graphexplorer.graphviz.EngineRouting.usesDotEngine(dot)

  /** Loads viz-js (needed for the non-`dot` layout engines); the pure-Scala
    * `dot` engine needs no async init but shares this instance's lifecycle. */
  def build(): Future[Graphviz] =
    VizJS.instance()
      .`then`(viz => Graphviz(viz))
      .toFuture
