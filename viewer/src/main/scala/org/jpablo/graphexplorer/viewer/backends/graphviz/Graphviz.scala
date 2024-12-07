package org.jpablo.graphexplorer.viewer.backends.graphviz

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.SvgDotDiagram
import org.jpablo.graphexplorer.viewer.formats.dot.DotText
import org.jpablo.graphexplorer.viewer.state.log
import org.scalajs.dom
import org.scalajs.dom.SVGSVGElement

class Graphviz:
  private val instance =
    val vizInst = VizJS.instance()
    dom.console.log(vizInst)
    Signal.fromJsPromise(vizInst)

  private def renderSVGElement(g: String) =
    instance
      .map { vizOpt =>
        vizOpt.map: viz =>
          log("[renderSVGElement][1]", ignore = true)(viz.renderSVGElement(g).asInstanceOf[SVGSVGElement])
      }
//      .recoverWith:
//        case e: Throwable =>
//          dom.console.log("==> renderSVGElement failed")
//          dom.console.log(e.toString)
//          dom.console.log(g)
//          Future.failed(e)

  def renderToSvg(dot: DotText): Signal[SVGSVGElement] =
    renderSVGElement(dot.value).map(_.getOrElse(SvgDotDiagram.empty.ref))
