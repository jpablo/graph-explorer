package org.jpablo.graphexplorer.viewer.backends.graphviz

import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.typings.{SimpleGraph, SimpleGraphConverter}
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.{ArrowPosition, Viz, VizJS}
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
      val result  = viz.renderFormats(dotText, js.Array("json0"))
      val dotJson = result.output("json0")
//      dom.console.log(js.JSON.parse(dotJson))
      read[SimpleGraph](dotJson) // upickle default reads the JSON string into a SimpleGraph instance
      //      js.JSON.parse(dotJson).asInstanceOf[SimpleGraph]

    }

  def renderToSvg(dot: DotText): Try[SvgWithPositions] =
    Try {
      val result       = viz.renderFormats(dot.value, js.Array("canon", "dot", "xdot", "json0", "json", "svg", "dot_json"))
      val result_svg   = result.output("svg")
      val result_json0 = result.output("json0")
      val graph        = read[SimpleGraph](result_json0) // upickle default reads the JSON string into a SimpleGraph instance
      //      val graph        = js.JSON.parse(result_json0).asInstanceOf[SimpleGraph]
      val edgePos = SimpleGraphConverter.getEdgePos(graph)
      SvgWithPositions(parseSVG(result_svg), edgePos)
    }

object Graphviz:

  def build(): Future[Graphviz] =
    VizJS.instance()
      .`then`(viz => Graphviz(viz))
      .toFuture
