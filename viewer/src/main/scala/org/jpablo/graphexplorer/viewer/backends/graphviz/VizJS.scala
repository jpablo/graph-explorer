package org.jpablo.graphexplorer.viewer.backends.graphviz

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

// https://github.com/mdaines/viz-js?tab=readme-ov-file

@js.native
@JSImport("@viz-js/viz", JSImport.Namespace)
object VizJS extends js.Object:
  def instance(): js.Promise[Viz] = js.native

@js.native
trait Viz extends js.Object:
  def renderSVGElement(dot: String): org.scalajs.dom.SVGElement = js.native
