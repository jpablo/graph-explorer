package org.jpablo.graphexplorer.viewer.state

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js

/** In 2D, a zoom or a pan of the user sets Auto to off, as in 3D.
  *
  * A diagram opens with Auto on. With Auto on, each new layout fits the
  * diagram again. Before, a 2D zoom or pan kept Auto on, so the next layout
  * (a hide, a fold) removed the zoom of the user. A move of the app itself
  * (Fit, the anchor after a layout) keeps Auto as it is.
  */
class AutoOffOnUserMoveSpec extends FunSuite with TestHelpers:

  import com.raquo.laminar.api.L.unsafeWindowOwner
  given com.raquo.airstream.ownership.Owner = unsafeWindowOwner

  override def munitFixtures = List(mockStorageFixture())

  private val svgNs = "http://www.w3.org/2000/svg"

  /** A real SVG in the document, so that `getScreenCTM` gives a value. */
  private def canvas(): (dom.svg.SVG, dom.svg.G) =
    val svg = dom.document.createElementNS(svgNs, "svg").asInstanceOf[dom.svg.SVG]
    svg.setAttribute("viewBox", "0 0 400 300")
    svg.setAttribute("width", "400")
    svg.setAttribute("height", "300")
    val g = dom.document.createElementNS(svgNs, "g").asInstanceOf[dom.svg.G]
    svg.appendChild(g)
    dom.document.body.appendChild(svg)
    (svg, g)

  private def wheel(deltaX: Double, deltaY: Double, meta: Boolean): dom.WheelEvent =
    val init = js.Dynamic
      .literal(deltaX = deltaX, deltaY = deltaY, metaKey = meta, clientX = 100, clientY = 100)
      .asInstanceOf[dom.WheelEventInit]
    new dom.WheelEvent("wheel", init)

  private def opened(name: String)(check: ViewerState => Unit) =
    withGraphvizAsync { graphviz =>
      val state = ViewerState(ViewTarget.diagram(name), graphviz, _ => ())
      afterMicrotasks {
        assert(state.autoFit.now(), "precondition: Auto is on when the diagram opens")
        check(state)
      }
    }

  test("the zoom-in button sets Auto to off"):
    opened("auto-off-zoom-in") { state =>
      state.zoomIn()
      assert(!state.autoFit.now(), "Auto is still on after a zoom in")
    }

  test("the zoom-out button sets Auto to off"):
    opened("auto-off-zoom-out") { state =>
      state.zoomOut()
      assert(!state.autoFit.now(), "Auto is still on after a zoom out")
    }

  test("a wheel zoom (with the command key) sets Auto to off"):
    opened("auto-off-wheel-zoom") { state =>
      val (svg, g) = canvas()
      try
        state.handleWheel(wheel(0, -40, meta = true), svg.viewBox.baseVal, g)
        assert(!state.autoFit.now(), "Auto is still on after a wheel zoom")
      finally svg.remove()
    }

  test("a wheel pan sets Auto to off"):
    opened("auto-off-wheel-pan") { state =>
      val (svg, g) = canvas()
      try
        state.handleWheel(wheel(30, 20, meta = false), svg.viewBox.baseVal, g)
        assert(!state.autoFit.now(), "Auto is still on after a wheel pan")
      finally svg.remove()
    }

  test("the Fit button keeps Auto on"):
    opened("auto-keep-fit") { state =>
      state.resetView()
      assert(state.autoFit.now(), "Fit set Auto to off")
    }

  test("the anchor after a layout keeps Auto on"):
    opened("auto-keep-anchor") { state =>
      state.panCompensateClient(12, -8, 1.0, 1.0)
      assert(state.autoFit.now(), "the anchor pan set Auto to off")
    }

  test("a zoom after Auto is off keeps Auto off, and the toggle turns it on again"):
    opened("auto-toggle-again") { state =>
      state.zoomIn()
      state.zoomIn()
      assert(!state.autoFit.now())
      state.autoFitToggle()
      assert(state.autoFit.now(), "the toggle did not turn Auto on again")
    }
