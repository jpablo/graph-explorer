package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.utils.SvgPoint

trait SvgTransformOps:
  this: ViewerState =>

  private val translateXY = Var(SvgPoint.origin)
  private val zoomValue   = Var(1.0)
  private val minZoom     = 0.05

  val autoFit = Var(false)

  val fitDiagram = EventBus[Unit]()

  def autoFitToggle() =
    autoFit.update(!_)

  def zoomOut() =
    zoomValue.update(_ * .9 max minZoom)

  def zoomIn() =
    zoomValue.update(_ * 1.1)

  val transform =
    zoomValue.signal
      .combineWithFn(translateXY.signal): (z, p) =>
        s"scale($z) translate(${p.x} ${p.y})"

  def resetView(): Unit =
    Var.set(
      zoomValue   -> 0.90,
      translateXY -> SvgPoint.origin
    )

  /** Pan the canvas by a CLIENT-space delta (px), with wheel semantics: a
    * positive `dx` scrolls the view right (content moves left). Same
    * client→user-space conversion as [[handleWheel]]'s pan branch.
    */
  def panByClient(dx: Double, dy: Double, viewBox: dom.SVGRect): Unit =
    val clientHeight = dom.window.innerHeight max 1
    val clientWidth  = dom.window.innerWidth max 1
    val z            = zoomValue.now()
    val scale        = viewBox.width / clientWidth max viewBox.height / clientHeight
    translateXY.update(_ - SvgPoint(dx * scale / z, dy * scale / z))

  def handleWheel(wEv: dom.WheelEvent, viewBox: dom.SVGRect) =
    val clientHeight = dom.window.innerHeight max 1
    val clientWidth  = dom.window.innerWidth max 1

    if wEv.metaKey && wEv.deltaY != 0 then
      zoomValue.update: z =>
        z - wEv.deltaY / clientHeight max minZoom
    else
      val z     = zoomValue.now()
      val scale = viewBox.width / clientWidth max viewBox.height / clientHeight
      val delta = SvgPoint(wEv.deltaX * scale / z, wEv.deltaY * scale / z)
      translateXY.update(_ - delta)
