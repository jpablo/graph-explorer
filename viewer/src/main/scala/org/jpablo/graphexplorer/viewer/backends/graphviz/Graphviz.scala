package org.jpablo.graphexplorer.viewer.backends.graphviz

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.formats.dot.DotText

class Graphviz:
  private val instance =
    val vizInst = VizJS.instance()
    Signal.fromJsPromise(vizInst)

  //      .recoverWith:
//        case e: Throwable =>
//          dom.console.log("==> renderSVGElement failed")
//          dom.console.log(e.toString)
//          dom.console.log(g)
//          Future.failed(e)

  def renderToSvg(dot: DotText): Signal[Option[ReactiveSvgElement[dom.svg.SVG]]] =
    instance.map(_.map: viz =>
      foreignSvgElement(svg.svg, viz.renderSVGElement(dot.value)))
