package org.jpablo.graphexplorer.viewer.components.svgCanvas

import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.models.{ElementIds, NodeId}
import org.jpablo.graphexplorer.viewer.utils.{TestHelpers, Waits}
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** The screen-constant chrome keeps its screen size after a zoom.
  *
  * The new-arrow control of a selected node has a constant size on the screen.
  * The selection casing of a node has a width on the screen that comes from
  * the stroke of the node at the current zoom. A zoom changes the scale of the
  * drawing, and the canvas then refits the chrome. This spec reads the chrome
  * on the screen after a zoom, and compares it with a fresh measure.
  */
class ScreenChromeZoomSpec extends FunSuite with TestHelpers with LoggedChecks with CanvasFixture:

  def checkPrefix     = "ScreenChromeZoomSpec"
  def canvasDiagramId = "screen-chrome-zoom"

  override def munitFixtures = List(mockStorageFixture())

  private val dot =
    """digraph G {
      |  subgraph cluster_x { label="group"; a; b; }
      |  a -> b; b -> c; c -> a; c -> d;
      |}""".stripMargin

  private val Tolerance = 0.5

  private def nextFrame(): Future[Unit] =
    val p = Promise[Unit]()
    dom.window.requestAnimationFrame(_ => p.success(()))
    p.future

  private def frames(n: Int): Future[Unit] =
    if n <= 0 then Future.unit else nextFrame().flatMap(_ => frames(n - 1))

  /** Send `n` zoom wheel events at the center of the canvas, one in each frame. */
  private def zoom(canvas: TestCanvas, deltaY: Double, n: Int): Future[Unit] =
    val box = canvas.svg.getBoundingClientRect()
    val init = js.Dynamic.literal(
      deltaY = deltaY,
      metaKey = true,
      clientX = box.left + box.width / 2,
      clientY = box.top + box.height / 2,
      bubbles = true,
      cancelable = true
    )
    def loop(i: Int): Future[Unit] =
      if i >= n then frames(3)
      else
        nextFrame().flatMap: _ =>
          canvas.svg.dispatchEvent(new dom.WheelEvent("wheel", init.asInstanceOf[dom.WheelEventInit]))
          loop(i + 1)
    loop(0)

  private def selectA(canvas: TestCanvas): Future[Unit] =
    canvas.state.selection.set(ElementIds.from(NodeId("a")))
    Waits
      .settle(canvas.box.querySelector("g.new-arrow-control") != null, timeoutMs = 5000)
      .map: _ =>
        check(
          canvas.box.querySelector("g.new-arrow-control") != null,
          "precondition: the canvas shows no new-arrow control for the selected node"
        )

  /** The client width of the circle of the first new-arrow control. */
  private def controlWidth(canvas: TestCanvas): Double =
    canvas.box.querySelector("g.new-arrow-control circle").getBoundingClientRect().width

  /** Client px per user unit of `el`, from a fresh measure. */
  private def scaleOf(el: dom.Element): Double =
    math.abs(el.asInstanceOf[dom.svg.G].getScreenCTM().a)

  /** The casing ring of the node `a`: the distance on the screen from the measured box of the node to the center line of
    * the ring, and the value that the casing rule gives at the current zoom.
    */
  private def casingOffsets(canvas: TestCanvas): (measured: Double, expected: Double) =
    val node  = canvas.nodeGroup(NodeId("a")).get
    val ring  = node.querySelector(s"rect.${SelectableElement.selectionRectClass}")
    val s     = scaleOf(node)
    val bw    = ring.getAttribute(SelectableElement.baseBoxAttr.w).toDouble
    val ringW = ring.getBoundingClientRect().width
    val shape = node.querySelector("ellipse, polygon")
    val own   = dom.window.getComputedStyle(shape).strokeWidth.stripSuffix("px").toDouble * s
    val band  = math.max(7.0, own + math.max(5.0, own * 1.6))
    (measured = (ringW - bw * s) / 2, expected = own / 2 + band / 2)

  private def checkChrome(canvas: TestCanvas, controlBefore: Double, when: String): Unit =
    val w = controlWidth(canvas)
    check(
      math.abs(w - controlBefore) <= Tolerance,
      f"$when: the new-arrow control is $w%.2f px wide, not $controlBefore%.2f px"
    )
    val c = casingOffsets(canvas)
    check(
      math.abs(c.measured - c.expected) <= Tolerance,
      f"$when: the casing ring is ${c.measured}%.2f px from the node, not ${c.expected}%.2f px"
    )

  test("a zoom in and a zoom out keep the screen size of the chrome"):
    withCanvas(dot, NodeId("a")): canvas =>
      selectA(canvas)
        .flatMap(_ => frames(2))
        .flatMap: _ =>
          val before = controlWidth(canvas)
          check(before > 4, f"precondition: the new-arrow control is only $before%.2f px wide")
          checkChrome(canvas, before, "before the zoom")
          zoom(canvas, deltaY = -60, n = 6)
            .map(_ => checkChrome(canvas, before, "after a zoom in"))
            .flatMap(_ => zoom(canvas, deltaY = 60, n = 10))
            .map(_ => checkChrome(canvas, before, "after a zoom out"))

  /** Select the node `a`, make the canvas box narrower, and run `body` with the client width of the new-arrow control
    * before the resize.
    */
  private def afterResize(canvas: TestCanvas)(body: Double => Future[Unit]): Future[Unit] =
    selectA(canvas)
      .flatMap(_ => frames(2))
      .flatMap: _ =>
        val before = controlWidth(canvas)
        val svgW   = canvas.svg.getBoundingClientRect().width
        canvas.box.style.width = "450px"
        frames(4).flatMap: _ =>
          check(
            math.abs(canvas.svg.getBoundingClientRect().width - svgW) > 50,
            "precondition: the svg did not change its width with the box"
          )
          body(before)

  test("after a resize of the canvas, a zoom keeps the screen size of the chrome"):
    withCanvas(dot, NodeId("a")): canvas =>
      afterResize(canvas): before =>
        zoom(canvas, deltaY = -60, n = 4)
          .map(_ => checkChrome(canvas, before, "after the resize and a zoom in"))
          .flatMap(_ => zoom(canvas, deltaY = 60, n = 6))
          .map(_ => checkChrome(canvas, before, "after the resize and a zoom out"))

  // A resize of the box does not resize the window. Before, only a window
  // resize and a transform write refitted the chrome, and the new-arrow
  // control kept its old scale: 9 px, not 16 px, in a box of 450 px.
  test("a resize of the canvas keeps the screen size of the chrome"):
    withCanvas(dot, NodeId("a")): canvas =>
      afterResize(canvas): before =>
        checkChrome(canvas, before, "after the resize")
        Future.unit
