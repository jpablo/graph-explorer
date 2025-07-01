package org.jpablo.graphexplorer.viewer.backends.graphviz

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.{ArrowPosition, Graph, VizJS}
import org.jpablo.graphexplorer.viewer.domUtils.parseSVG
import org.jpablo.graphexplorer.viewer.formats.dot.DotText

import scala.scalajs.js

case class SvgWithPositions(
  svg: ReactiveSvgElement[dom.svg.SVG],
  edgePositions: Map[String, ArrowPosition]
)

class Graphviz:
  private val instance =
    Signal.fromJsPromise(VizJS.instance())

  //      .recoverWith:
//        case e: Throwable =>
//          dom.console.log("==> renderSVGElement failed")
//          dom.console.log(e.toString)
//          dom.console.log(g)
//          Future.failed(e)

  def renderToSvg(dot: DotText): Signal[Option[SvgWithPositions]] =
    instance.map(_.map: viz =>
//      dom.console.log(viz.formats)
      val result  = viz.renderFormats(dot.value, js.Array(/*"xdot_json", "dot_json", "json", */"json0", "svg"))
      val svgText = result.output("svg")
      val dotJson = result.output("json0")
//      dom.console.log(result.output)
      val graph: Graph = js.JSON.parse(dotJson).asInstanceOf[Graph]
      val edgePos = Graph.getEdgePos(graph)
      SvgWithPositions(parseSVG(svgText), edgePos))
