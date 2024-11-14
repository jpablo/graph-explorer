package org.jpablo.graphexplorer.viewer.backends.graphviz

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.SvgDotDiagram
import org.jpablo.graphexplorer.viewer.formats.dot.DotText
import org.scalajs.dom
import org.scalajs.dom.SVGSVGElement

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

class Graphviz:
  private val instance: Future[Viz] =
    VizJS.instance().toFuture

  private def renderSVGElement(g: String): Future[dom.SVGSVGElement] =
    instance
      .map(_.renderSVGElement(g).asInstanceOf[dom.SVGSVGElement])
      .recoverWith:
        case e: Throwable =>
          dom.console.log("==> renderSVGElement failed")
          dom.console.log(e.toString)
          dom.console.log(g)
          Future.failed(e)

  def renderToSvg(dot: DotText): Signal[SVGSVGElement] =
    Signal
      .fromFuture(renderSVGElement(dot.value))
      .map(_.getOrElse(SvgDotDiagram.empty.ref))
