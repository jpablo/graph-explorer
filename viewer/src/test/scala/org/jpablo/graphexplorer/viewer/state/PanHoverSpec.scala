package org.jpablo.graphexplorer.viewer.state

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** While a pan moves the drawing, the drawing takes no pointer events.
  *
  * During a trackpad pan, the pointer stays still and the drawing moves under
  * it. The browser then hit-tests the drawing in each frame and sends
  * `mouseover` to each node and group that passes under the pointer. On an
  * editable diagram, a hover handler then shows and hides the fold badges of
  * the groups. In the desktop window, a pan of an editable diagram felt less
  * smooth than a pan of a view-only diagram, which has no such handler.
  *
  * `panning` is true from the first input of a pan until the pan settles. The
  * canvas uses it to turn off the pointer events of the drawing.
  *
  * The checks throw AssertionError, not munit's `assert`: through the Scala.js
  * test bridge and the sbt client, a munit FailException arrives with no message.
  */
class PanHoverSpec extends FunSuite with TestHelpers:

  import com.raquo.laminar.api.L.unsafeWindowOwner
  given com.raquo.airstream.ownership.Owner = unsafeWindowOwner

  override def munitFixtures = List(mockStorageFixture())
  override def munitTimeout  = scala.concurrent.duration.Duration(20, "s")

  private def check(condition: Boolean, message: => String): Unit =
    if !condition then throw AssertionError(message)

  private val svgNs = "http://www.w3.org/2000/svg"

  private def canvas(): (dom.svg.SVG, dom.svg.G) =
    val svg = dom.document.createElementNS(svgNs, "svg").asInstanceOf[dom.svg.SVG]
    svg.setAttribute("viewBox", "0 0 400 300")
    svg.setAttribute("width", "400")
    svg.setAttribute("height", "300")
    val g = dom.document.createElementNS(svgNs, "g").asInstanceOf[dom.svg.G]
    svg.appendChild(g)
    dom.document.body.appendChild(svg)
    (svg, g)

  private def wheel(deltaX: Double, deltaY: Double, meta: Boolean = false): dom.WheelEvent =
    val init = js.Dynamic
      .literal(deltaX = deltaX, deltaY = deltaY, metaKey = meta, clientX = 100, clientY = 100)
      .asInstanceOf[dom.WheelEventInit]
    new dom.WheelEvent("wheel", init)

  /** Completes when `cond` is true, or fails after `ms`. */
  private def eventually(ms: Double, what: String)(cond: => Boolean): Future[Unit] =
    val p     = Promise[Unit]()
    val start = dom.window.performance.now()
    def poll(): Unit =
      if cond then p.success(())
      else if dom.window.performance.now() - start > ms then p.failure(AssertionError(what))
      else js.timers.setTimeout(10)(poll())
    poll()
    p.future

  private def opened(name: String)(body: ViewerState => Future[Unit]) =
    withGraphvizAsync { graphviz =>
      val state = ViewerState(ViewTarget.diagram(name), graphviz, _ => ())
      afterMicrotasks(()).flatMap(_ => body(state))
    }

  test("a wheel pan sets panning on, and panning goes off when the pan settles"):
    opened("pan-hover-wheel") { state =>
      val (svg, g) = canvas()
      var now      = false
      state.panning.foreach(now = _)
      check(!now, "panning is on before any input")
      state.handleWheel(wheel(20, 10), svg.viewBox.baseVal, g)
      check(now, "panning is off right after a wheel pan")
      eventually(3000, "panning is still on 3 s after the last input")(!now).map(_ => svg.remove())
    }

  test("a zoom with the wheel is not a pan"):
    opened("pan-hover-zoom") { state =>
      val (svg, g) = canvas()
      var now      = false
      state.panning.foreach(now = _)
      state.handleWheel(wheel(0, -30, meta = true), svg.viewBox.baseVal, g)
      check(!now, "panning is on after a zoom")
      svg.remove()
      Future.unit
    }

  test("Fit during a pan ends the pan"):
    opened("pan-hover-fit") { state =>
      val (svg, g) = canvas()
      var now      = false
      state.panning.foreach(now = _)
      state.handleWheel(wheel(20, 10), svg.viewBox.baseVal, g)
      state.resetView()
      check(!now, "panning is still on after Fit")
      svg.remove()
      Future.unit
    }
