package org.jpablo.typeexplorer.viewer.backends.graphviz

import com.raquo.laminar.api.L.*
import org.jpablo.typeexplorer.viewer.components.SvgDotDiagram
import org.jpablo.typeexplorer.viewer.formats.dot.Dot
import org.scalajs.dom
import org.scalajs.dom.SVGSVGElement

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

class Graphviz:
  private val instance: Future[js.Dynamic] =
    js.Dynamic.global.Viz
      .instance()
      .asInstanceOf[js.Promise[js.Dynamic]]
      .toFuture

  private def renderSVGElement(g: String): Future[dom.SVGSVGElement] =
    instance
      .map(_.renderSVGElement(g).asInstanceOf[dom.SVGSVGElement])
      .transform {
        case scala.util.Success(value) =>
          scala.util.Success(value)
        case scala.util.Failure(exception) =>
          dom.console.log("==> renderSVGElement failed")
          dom.console.log(exception.toString)
          scala.util.Failure(exception)
      }

  def renderToSvg(dot: Dot): Signal[SVGSVGElement] =
    Signal
      .fromFuture(renderSVGElement(dot.value))
      .map(_.getOrElse(SvgDotDiagram.empty.ref))

