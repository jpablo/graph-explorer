package org.jpablo.graphexplorer.viewer.backends.graphviz

import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.{SimpleGraph, SimpleGraphConverter, ArrowPosition}
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.{Viz, VizJS}
import org.jpablo.graphexplorer.viewer.domUtils.parseSVG
import org.jpablo.graphexplorer.viewer.formats.dot.DotText
import upickle.default.*

import scala.concurrent.Future

import scala.scalajs.js
import scala.util.Try

case class SvgWithPositions(
    svg:           ReactiveSvgElement[dom.svg.SVG],
    edgePositions: Map[String, ArrowPosition]
)

class Graphviz(viz: Viz):
  def renderToJsonGraph(dotText: String): Try[SimpleGraph] =
    Try {
      val result = viz.renderFormats(dotText, js.Array("json0"))
//      dom.console.group("Graphviz.renderToJsonGraph")
//      dom.console.log(dotText)
//      dom.console.log(result)
//      dom.console.groupEnd()
      val dotJson = result.output("json0")
//      dom.console.log(js.JSON.parse(dotJson))
      read[SimpleGraph](dotJson) // upickle default reads the JSON string into a SimpleGraph instance
      //      js.JSON.parse(dotJson).asInstanceOf[SimpleGraph]

    }

  def renderToSvg(dot: DotText): Try[SvgWithPositions] =
    Try {
      // all formats js.Array("canon", "dot", "xdot", "json0", "json", "svg", "dot_json")
      val result = viz.renderFormats(dot.value, js.Array("svg", "json0"))
      if result.status == "success" then
        val result_svg   = result.output("svg")
        val result_json0 = result.output("json0")
        val graph        = read[SimpleGraph](result_json0)
        val edgePos      = SimpleGraphConverter.getEdgePos(graph)
        SvgWithPositions(parseSVG(result_svg), edgePos)
      else
        dom.console.group("Graphviz.renderToSvg")
        dom.console.error(dot.value)
        dom.console.error(result)
        dom.console.groupEnd()
        throw new Exception(s"Graphviz rendering failed: ${result.status} - ${result.errors}")
    }

object Graphviz:

  def build(): Future[Graphviz] =
    VizJS.instance()
      .`then`(viz => Graphviz(viz))
      .toFuture
