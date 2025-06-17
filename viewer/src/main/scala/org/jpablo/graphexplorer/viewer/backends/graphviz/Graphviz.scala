package org.jpablo.graphexplorer.viewer.backends.graphviz

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.formats.dot.DotText

//import scala.scalajs.js

class Graphviz:
  private val instance =
    Signal.fromJsPromise(VizJS.instance())

  //      .recoverWith:
//        case e: Throwable =>
//          dom.console.log("==> renderSVGElement failed")
//          dom.console.log(e.toString)
//          dom.console.log(g)
//          Future.failed(e)

  def renderToSvg(dot: DotText): Signal[Option[ReactiveSvgElement[dom.svg.SVG]]] =
    instance.map(_.map: viz =>
//      dom.console.log(viz)
//      dom.console.log(viz.graphvizVersion)
//      dom.console.log(viz.formats)
//      dom.console.log(viz.engines)
//      dom.console.log("renderString")
//      dom.console.log(viz.renderString(dot.value))
//      val result = viz.renderFormats(dot.value, viz.formats)
//      dom.console.log("renderFormats")
//      dom.console.log(js.JSON.stringify(result.output))
//      dom.console.log(result.errors)
      foreignSvgElement(svg.svg, viz.renderSVGElement(dot.value)))
