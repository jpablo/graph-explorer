package org.jpablo.graphexplorer.viewer.components.svgCanvas

import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.viewer.models.{ElementIds, NodeId}
import org.jpablo.graphexplorer.viewer.utils.{TestHelpers, Waits}
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** A pan or a zoom of the canvas does not force a layout.
  *
  * The canvas writes the transform of its drawing group for each frame of a
  * pan or a zoom. Then it refits the screen-constant chrome: the selection
  * casing and the new-arrow controls. Before, the refit read the scale from
  * `getScreenCTM`. After a transform write, that read makes the browser lay
  * out the whole SVG at once. WebKit (the desktop window) does not need that
  * layout to draw a new transform. Measured with
  * `scripts/webkit-pan-zoom-perf.sh`, 360 nodes (5,376 SVG elements): 20 to
  * 36 ms of script in each frame, and frames of 25 ms to 400 ms. With no
  * such read: under 0.5 ms of script, and frames of 17 ms (60 Hz).
  *
  * The test counts the layout reads that occur after a transform write of the
  * drawing group and before the next animation frame. A read there forces a
  * layout. A read at the start of a frame is free, because the browser laid
  * out the page for the frame before.
  */
class PanZoomLayoutSpec extends FunSuite with TestHelpers with LoggedChecks with CanvasFixture:

  def checkPrefix     = "PanZoomLayoutSpec"
  def canvasDiagramId = "pan-zoom-layout"

  override def munitFixtures = List(mockStorageFixture())

  private val dot =
    """digraph G {
      |  subgraph cluster_x { label="group"; a; b; }
      |  a -> b; b -> c; c -> a; c -> d;
      |}""".stripMargin

  private def nextFrame(): Future[Unit] =
    val p = Promise[Unit]()
    dom.window.requestAnimationFrame(_ => p.success(()))
    p.future

  private def frames(n: Int): Future[Unit] =
    if n <= 0 then Future.unit else nextFrame().flatMap(_ => frames(n - 1))

  /** Watch the page for layout reads after a write of the canvas transform.
    *
    * A write of `transform` on `g.gx-canvas-root` marks the layout as dirty. The
    * first animation-frame callback of each frame marks it as clean: the
    * watcher puts its own callback first in each frame. Each layout read while
    * the layout is dirty goes into `forced`, with its name.
    */
  private def watchForcedLayouts(): js.Dynamic =
    js.eval(
      """(function() {
        |  var forced = [], dirty = false, on = true, restore = [];
        |  function tick() { dirty = false; if (on) requestAnimationFrame(tick); }
        |  requestAnimationFrame(tick);
        |  function wrap(proto, name) {
        |    var raw = proto[name];
        |    proto[name] = function() {
        |      if (on && dirty) forced.push(name);
        |      return raw.apply(this, arguments);
        |    };
        |    restore.push(function() { proto[name] = raw; });
        |  }
        |  wrap(SVGGraphicsElement.prototype, 'getScreenCTM');
        |  wrap(SVGGraphicsElement.prototype, 'getCTM');
        |  wrap(SVGGraphicsElement.prototype, 'getBBox');
        |  wrap(Element.prototype, 'getBoundingClientRect');
        |  wrap(Element.prototype, 'getClientRects');
        |  var rawGcs = window.getComputedStyle;
        |  window.getComputedStyle = function() {
        |    if (on && dirty) forced.push('getComputedStyle');
        |    return rawGcs.apply(window, arguments);
        |  };
        |  restore.push(function() { window.getComputedStyle = rawGcs; });
        |  var rawSet = Element.prototype.setAttribute;
        |  Element.prototype.setAttribute = function(name, value) {
        |    if (on && name === 'transform' && this.classList && this.classList.contains('gx-canvas-root')) dirty = true;
        |    return rawSet.call(this, name, value);
        |  };
        |  restore.push(function() { Element.prototype.setAttribute = rawSet; });
        |  return {
        |    forced: forced,
        |    stop: function() { on = false; restore.forEach(function(f) { f(); }); }
        |  };
        |})()""".stripMargin
    ).asInstanceOf[js.Dynamic]

  /** A wheel event at the center `at` of the canvas. The caller reads `at`
    * before the gesture, so that the test itself reads no layout in it.
    */
  private def wheel(svg: dom.Element, at: (Double, Double), zoom: Boolean, step: Int): Unit =
    val out = step % 10 < 5
    val init = js.Dynamic.literal(
      deltaX = if zoom then 0 else if out then 6 else -6,
      deltaY = if out then -3 else 3,
      metaKey = zoom,
      clientX = at._1,
      clientY = at._2,
      bubbles = true,
      cancelable = true
    )
    svg.dispatchEvent(new dom.WheelEvent("wheel", init.asInstanceOf[dom.WheelEventInit]))

  /** Send `n` wheel events, one in each frame, and give back the forced layout reads. */
  private def gesture(canvas: TestCanvas, zoom: Boolean, n: Int): Future[List[String]] =
    val box     = canvas.svg.getBoundingClientRect()
    val at      = (box.left + box.width / 2, box.top + box.height / 2)
    val watcher = watchForcedLayouts()
    def loop(i: Int): Future[Unit] =
      if i >= n then frames(3)
      else
        nextFrame().flatMap: _ =>
          wheel(canvas.svg, at, zoom, i)
          loop(i + 1)
    // The first frame puts the watcher callback before the callbacks of the app.
    nextFrame()
      .flatMap(_ => loop(0))
      .map: _ =>
        watcher.stop()
        watcher.forced.asInstanceOf[js.Array[String]].toList

  private def summary(reads: List[String]): String =
    reads.groupBy(identity).map((name, l) => s"$name x${l.size}").mkString(", ")

  /** Select the node `a`, and wait until the canvas shows its casing and its new-arrow controls. */
  private def selectA(canvas: TestCanvas): Future[Unit] =
    canvas.state.selection.set(ElementIds.from(NodeId("a")))
    Waits
      .settle(canvas.box.querySelector("g.new-arrow-control") != null, timeoutMs = 5000)
      .map: _ =>
        check(
          canvas.nodeGroup(NodeId("a")).exists(_.classList.contains("selected")),
          "precondition: the node a is not selected"
        )
        check(
          canvas.box.querySelector("g.new-arrow-control") != null,
          "precondition: the canvas shows no new-arrow control for the selected node"
        )

  test("a pan with a selected node forces no layout"):
    withCanvas(dot, NodeId("a")): canvas =>
      selectA(canvas)
        .flatMap(_ => gesture(canvas, zoom = false, n = 20))
        .map: forced =>
          check(forced.isEmpty, s"the pan forced ${forced.size} layouts in 20 events: ${summary(forced)}")

  test("a zoom with a selected node forces no layout"):
    withCanvas(dot, NodeId("a")): canvas =>
      selectA(canvas)
        .flatMap(_ => gesture(canvas, zoom = true, n = 20))
        .map: forced =>
          check(forced.isEmpty, s"the zoom forced ${forced.size} layouts in 20 events: ${summary(forced)}")

  test("a pan and a zoom with no selection force no layout"):
    withCanvas(dot, NodeId("a")): canvas =>
      gesture(canvas, zoom = false, n = 10)
        .flatMap(pan => gesture(canvas, zoom = true, n = 10).map(pan ++ _))
        .map: forced =>
          check(forced.isEmpty, s"the gestures forced ${forced.size} layouts in 20 events: ${summary(forced)}")
