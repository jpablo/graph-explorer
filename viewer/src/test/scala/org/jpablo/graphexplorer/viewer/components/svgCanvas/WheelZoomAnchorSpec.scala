package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.render
import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.components.{Commands, RouterCommands}
import org.jpablo.graphexplorer.viewer.state.{ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.{TestHelpers, Waits}
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** A wheel zoom with the command key keeps the point under the pointer still.
  *
  * The zoom scales the drawing group around its `transform-origin`. The canvas
  * sets that origin at the center of the viewBox. `handleWheel` reads the
  * origin and corrects the pan. This test uses a Mermaid drawing whose
  * viewBox does not start at (0, 0), so a wrong origin moves the point.
  */
class WheelZoomAnchorSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "WheelZoomAnchorSpec"

  override def munitFixtures = List(mockStorageFixture())

  /** Mermaid puts the note at the left of the first actor, so the viewBox starts at a negative x. */
  private val sequence =
    """sequenceDiagram
      |  participant A as systemd
      |  participant B as updater
      |  participant C as podman
      |  Note left of A: a long note at the left of the first actor
      |  A->>B: start service
      |  B->>C: start db container
      |  C-->>A: ok""".stripMargin

  private def wheel(clientX: Double, clientY: Double, deltaY: Double): dom.WheelEvent =
    val init = js.Dynamic
      .literal(deltaY = deltaY, metaKey = true, clientX = clientX, clientY = clientY, bubbles = true, cancelable = true)
      .asInstanceOf[dom.WheelEventInit]
    new dom.WheelEvent("wheel", init)

  /** The point of the drawing group that is at the client point (x, y). */
  private def userPointAt(group: dom.svg.G, svg: dom.svg.SVG, x: Double, y: Double): dom.SVGPoint =
    val p = svg.createSVGPoint()
    p.x = x
    p.y = y
    p.matrixTransform(group.getScreenCTM().inverse())

  /** The client point of the point `q` of the drawing group. */
  private def clientPointOf(group: dom.svg.G, q: dom.SVGPoint): dom.SVGPoint =
    q.matrixTransform(group.getScreenCTM())

  test("a cmd-wheel zoom keeps the point under the pointer still on a Mermaid drawing"):
    val startHref = dom.window.location.href
    val owner     = ManualOwner()
    withGraphvizAsync: graphviz =>
      val id       = "wheel-zoom-anchor"
      val state    = ViewerState(ViewTarget.diagram(id), graphviz, _ => (), initialSource = Some(sequence))(using owner)
      val commands = Commands(state, RouterCommands(Router()))
      val box      = dom.document.createElement("div").asInstanceOf[dom.html.Element]
      box.style.width = "800px"
      box.style.height = "600px"
      dom.document.body.appendChild(box)
      val root = render(box, CanvasContainer(state, commands))
      def drawn = Option(box.querySelector("svg")).exists(s => s.querySelector("g") != null && s.getAttribute("viewBox") != null)
      Waits
        .settle(drawn, timeoutMs = 10000)
        .flatMap: _ =>
          check(drawn, "precondition: the canvas shows no drawing")
          val container = box.querySelector("#canvas-container").asInstanceOf[dom.html.Element]
          val svg       = box.querySelector("svg").asInstanceOf[dom.svg.SVG]
          val group     = CanvasRoot.mainGroup(svg)
          val vb        = svg.viewBox.baseVal
          check(vb.x < -1, f"precondition: the viewBox starts at x = ${vb.x}%.1f, not left of 0")
          val rect = container.getBoundingClientRect()
          // Two points away from the center, with a zoom in and then a zoom out.
          val steps = List(
            (rect.left + rect.width * 0.2, rect.top + rect.height * 0.3, -60.0),
            (rect.left + rect.width * 0.75, rect.top + rect.height * 0.8, 45.0)
          )
          steps.foreach: (x, y, deltaY) =>
            val q        = userPointAt(group, svg, x, y)
            val oldScale = group.getScreenCTM().a
            container.dispatchEvent(wheel(x, y, deltaY))
            val newScale = group.getScreenCTM().a
            val after    = clientPointOf(group, q)
            check(
              (newScale - oldScale).abs > 1e-6,
              f"precondition: the wheel at ($x%.0f, $y%.0f) did not change the zoom (scale $oldScale%.4f)"
            )
            check(
              (after.x - x).abs <= 0.5 && (after.y - y).abs <= 0.5,
              f"the point under the pointer moved from ($x%.1f, $y%.1f) to (${after.x}%.1f, ${after.y}%.1f) " +
                f"after a zoom from $oldScale%.4f to $newScale%.4f"
            )
          Future.unit
        .transform: outcome =>
          root.unmount()
          box.parentNode.removeChild(box)
          owner.killSubscriptions()
          dom.window.history.replaceState(null, "", startHref)
          outcome
