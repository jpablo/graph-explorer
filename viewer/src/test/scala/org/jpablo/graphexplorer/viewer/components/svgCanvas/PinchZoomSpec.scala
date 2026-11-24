package org.jpablo.graphexplorer.viewer.components.svgCanvas

import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.viewer.models.NodeId
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.concurrent.Future
import scala.scalajs.js

/** A trackpad pinch zooms the canvas, and the point under the fingers stays still.
  *
  * The browser engines send a pinch in two forms:
  *   - Chromium (and Firefox) send wheel events with `ctrlKey = true`. A pinch by the
  *     factor `s` gives a `deltaY` of about `-100 * ln(s)`, so the canvas multiplies
  *     the zoom by `exp(-deltaY / 100)`.
  *   - WebKit (Safari and the macOS desktop window) sends `gesturestart`,
  *     `gesturechange`, and `gestureend`. The `scale` of each event is the factor
  *     since `gesturestart`, so the zoom is the zoom at the start multiplied by
  *     `scale`. The canvas cancels these events, so that Safari does not zoom the
  *     whole page.
  *
  * Chromium has no `GestureEvent`. The test makes a plain `Event` with the
  * properties of a WebKit `GestureEvent`: `scale`, `clientX`, and `clientY`.
  */
class PinchZoomSpec extends FunSuite with TestHelpers with LoggedChecks with CanvasFixture:

  def checkPrefix     = "PinchZoomSpec"
  def canvasDiagramId = "pinch-zoom"

  override def munitFixtures = List(mockStorageFixture())

  private val dot =
    """digraph G {
      |  subgraph cluster_x { label="group"; a; b; }
      |  a -> b; b -> c; c -> a; c -> d;
      |}""".stripMargin

  /** The tolerance of the zoom factor, as a ratio. */
  private val FactorTolerance = 0.02

  /** The tolerance of the anchor, in client px. */
  private val AnchorTolerance = 0.5

  private def ctrlWheel(x: Double, y: Double, deltaY: Double): dom.WheelEvent =
    val init = js.Dynamic
      .literal(deltaY = deltaY, ctrlKey = true, clientX = x, clientY = y, bubbles = true, cancelable = true)
      .asInstanceOf[dom.WheelEventInit]
    new dom.WheelEvent("wheel", init)

  /** An event with the properties of a WebKit `GestureEvent`. */
  private def gesture(kind: String, x: Double, y: Double, scale: Double): dom.Event =
    val ev = new dom.Event(kind, js.Dynamic.literal(bubbles = true, cancelable = true).asInstanceOf[dom.EventInit])
    def define(name: String, value: Double): Unit =
      js.Object.defineProperty(
        ev.asInstanceOf[js.Object],
        name,
        js.Dynamic.literal(value = value, enumerable = true).asInstanceOf[js.PropertyDescriptor]
      )
    define("scale", scale)
    define("rotation", 0)
    define("clientX", x)
    define("clientY", y)
    ev

  /** The client px per user unit of the drawing group. */
  private def scaleOf(canvas: TestCanvas): Double =
    CanvasRoot.mainGroup(canvas.svg).getScreenCTM().a

  /** The point of the drawing group that is at the client point (x, y). */
  private def userPointAt(canvas: TestCanvas, x: Double, y: Double): dom.SVGPoint =
    val p = canvas.svg.createSVGPoint()
    p.x = x
    p.y = y
    p.matrixTransform(CanvasRoot.mainGroup(canvas.svg).getScreenCTM().inverse())

  /** The client point of the point `q` of the drawing group. */
  private def clientPointOf(canvas: TestCanvas, q: dom.SVGPoint): dom.SVGPoint =
    q.matrixTransform(CanvasRoot.mainGroup(canvas.svg).getScreenCTM())

  /** A point of the canvas that is not at its center, so that a wrong anchor shows.
    *
    * The point is on whole pixels: Chromium removes the fraction of `clientX` and
    * `clientY` from a `WheelEvent`, and a zoom makes that difference larger.
    */
  private def fingers(canvas: TestCanvas): (Double, Double) =
    val r = canvas.svg.getBoundingClientRect()
    (math.floor(r.left + r.width * 0.3), math.floor(r.top + r.height * 0.7))

  private def checkFactor(what: String, before: Double, after: Double, expected: Double): Unit =
    val factor = after / before
    check(
      (factor / expected - 1).abs <= FactorTolerance,
      f"$what: the zoom changed by the factor $factor%.4f, not $expected%.4f (scale $before%.4f -> $after%.4f)"
    )

  private def checkAnchor(what: String, canvas: TestCanvas, q: dom.SVGPoint, x: Double, y: Double): Unit =
    val p = clientPointOf(canvas, q)
    check(
      (p.x - x).abs <= AnchorTolerance && (p.y - y).abs <= AnchorTolerance,
      f"$what: the point under the fingers moved from ($x%.1f, $y%.1f) to (${p.x}%.1f, ${p.y}%.1f)"
    )

  test("a Chromium pinch (wheel with ctrlKey) zooms by exp(-deltaY / 100) at the fingers"):
    withCanvas(dot, NodeId("a")): canvas =>
      val (x, y) = fingers(canvas)
      // Zoom in by 2, in four events, then zoom out by 2 in one event.
      val q      = userPointAt(canvas, x, y)
      val start  = scaleOf(canvas)
      val inStep = -100 * math.log(2) / 4
      (1 to 4).foreach(_ => canvas.svg.dispatchEvent(ctrlWheel(x, y, inStep)))
      val zoomedIn = scaleOf(canvas)
      checkFactor("pinch out (zoom in)", start, zoomedIn, 2.0)
      checkAnchor("pinch out (zoom in)", canvas, q, x, y)
      canvas.svg.dispatchEvent(ctrlWheel(x, y, 100 * math.log(2)))
      val zoomedOut = scaleOf(canvas)
      checkFactor("pinch in (zoom out)", zoomedIn, zoomedOut, 0.5)
      checkAnchor("pinch in (zoom out)", canvas, q, x, y)
      Future.unit

  test("a WebKit pinch (gesture events) zooms by the gesture scale at the fingers"):
    withCanvas(dot, NodeId("a")): canvas =>
      val (x, y) = fingers(canvas)
      val q      = userPointAt(canvas, x, y)
      val start  = scaleOf(canvas)
      val begin  = gesture("gesturestart", x, y, 1.0)
      canvas.svg.dispatchEvent(begin)
      check(begin.defaultPrevented, "the canvas did not cancel gesturestart, so Safari zooms the whole page")
      // The scale of each event is the factor since gesturestart, not since the event before.
      List(1.2, 1.6, 2.0).foreach: s =>
        val change = gesture("gesturechange", x, y, s)
        canvas.svg.dispatchEvent(change)
        check(change.defaultPrevented, f"the canvas did not cancel gesturechange (scale $s%.1f)")
        checkFactor(f"gesturechange to the scale $s%.1f", start, scaleOf(canvas), s)
        checkAnchor(f"gesturechange to the scale $s%.1f", canvas, q, x, y)
      canvas.svg.dispatchEvent(gesture("gestureend", x, y, 2.0))
      checkFactor("after gestureend", start, scaleOf(canvas), 2.0)
      // A second pinch starts from the zoom that the first pinch left.
      val second = scaleOf(canvas)
      canvas.svg.dispatchEvent(gesture("gesturestart", x, y, 1.0))
      canvas.svg.dispatchEvent(gesture("gesturechange", x, y, 0.5))
      canvas.svg.dispatchEvent(gesture("gestureend", x, y, 0.5))
      checkFactor("a second pinch to the scale 0.5", second, scaleOf(canvas), 0.5)
      checkAnchor("a second pinch to the scale 0.5", canvas, q, x, y)
      Future.unit

  test("a ctrl-wheel event during a WebKit pinch does not zoom a second time"):
    withCanvas(dot, NodeId("a")): canvas =>
      val (x, y) = fingers(canvas)
      val start  = scaleOf(canvas)
      canvas.svg.dispatchEvent(gesture("gesturestart", x, y, 1.0))
      canvas.svg.dispatchEvent(gesture("gesturechange", x, y, 1.5))
      canvas.svg.dispatchEvent(ctrlWheel(x, y, -100 * math.log(1.5)))
      canvas.svg.dispatchEvent(gesture("gestureend", x, y, 1.5))
      checkFactor("a pinch to 1.5 with one ctrl-wheel event in it", start, scaleOf(canvas), 1.5)
      Future.unit
