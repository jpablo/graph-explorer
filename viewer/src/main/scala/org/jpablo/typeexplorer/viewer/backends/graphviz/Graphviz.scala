package org.jpablo.typeexplorer.viewer.backends.graphviz

import com.raquo.laminar.api.L.*
import org.jpablo.typeexplorer.viewer.components.SvgDotDiagram
import org.jpablo.typeexplorer.viewer.formats.dot.Dot
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

  private def renderSVGElement(g: String): Future[SVGSVGElement] =
    instance.map(_.renderSVGElement(g).asInstanceOf[SVGSVGElement])

  def renderDot(dot: Dot): Signal[SvgDotDiagram] =
    Signal
      .fromFuture(renderSVGElement(dot.value).map(SvgDotDiagram(_)))
      .map(_.getOrElse(SvgDotDiagram.empty))
