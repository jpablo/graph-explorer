package org.jpablo.graphexplorer.viewer.components.svgCanvas

import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.viewer.models.{ElementIds, NodeId}
import org.jpablo.graphexplorer.viewer.utils.{TestHelpers, Waits}
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** The first frame of a layout transition in an automatic layout.
  *
  * `LayoutTransition` promises that frame 0 is exact: each element that is in the old and the new layout is at its OLD
  * place and size on the screen. Then it glides to the new place. The new view comes first: `afterLayoutSwap` fits the
  * view again (Auto) or pans to keep the focal node (anchor), and then it starts the transition. The zoom and the pan
  * are writes of `Var.set`. Inside the swap, Airstream runs a nested `Var.set` after the current transaction. If the
  * transition measures the new drawing before that write, frame 0 starts from a wrong frame, and the drawing jumps.
  *
  * Found on 2026-11-27 with the same cause in the manual mode: after a drop near an edge, the drawing jumped up and
  * grew, and then it came back.
  */
class TransitionFrameZeroSpec extends FunSuite with TestHelpers with LoggedChecks with CanvasFixture:

  def checkPrefix     = "TransitionFrameZeroSpec"
  def canvasDiagramId = "transition-frame-zero"

  // Through the Scala.js test bridge and the sbt client, a munit failure arrives with no message. An AssertionError
  // keeps its message.
  override def check(condition: Boolean, clue: => String): Unit =
    if !condition then throw AssertionError(s"[$checkPrefix] $clue")

  override def munitFixtures = List(mockStorageFixture())

  private val a = NodeId("a")
  private val b = NodeId("b")
  private val c = NodeId("c")

  private val small = "digraph G {\n  a -> b\n  b -> c\n}"
  // The same nodes, and more nodes under them and at the side: the bounding box of the drawing grows.
  private val large = "digraph G {\n  a -> b\n  b -> c\n  c -> d\n  d -> e\n  c -> f\n  c -> g\n  c -> h\n}"

  /** Give the svg the size of the canvas, as the app stylesheet does (see `NodeDragSpec.sizeLikeApp`). */
  private def sizeLikeApp(canvas: TestCanvas): Unit =
    val rules = dom.document.createElement("style")
    rules.textContent =
      ".floating-toolbar { position: absolute; } " +
        "#canvas-container { width: 100%; height: 100%; } " +
        "#canvas-container > svg { display: block; width: 100%; height: 100%; }"
    canvas.box.appendChild(rules)

  private def shapeOf(g: dom.Element): dom.Element =
    Option(g.querySelector("ellipse")).orElse(Option(g.querySelector("polygon"))).orElse(Option(g.querySelector("rect"))).get

  private type Box = (Double, Double, Double, Double)

  private def boxes(canvas: TestCanvas, ids: Seq[NodeId]): Map[NodeId, Box] =
    ids
      .map: id =>
        val r = shapeOf(canvas.nodeGroup(id).get).getBoundingClientRect()
        id -> (r.left, r.top, r.width, r.height)
      .toMap

  /** Stop `requestAnimationFrame`, with the page still visible: a transition starts and stays at frame 0. The
    * returned function puts the function back.
    */
  private def freezeFrames(): () => Unit =
    val win = dom.window.asInstanceOf[js.Dynamic]
    val raf = win.requestAnimationFrame
    win.requestAnimationFrame = ((_: js.Function1[Double, Unit]) => 0): js.Function1[js.Function1[Double, Unit], Int]
    () => win.requestAnimationFrame = raf

  /** Change the source from `small` to `large`, with the frames stopped, and compare the boxes of the old nodes at
    * frame 0 with their boxes before the change.
    */
  private def checkFrameZero(canvas: TestCanvas, what: String): Future[Unit] =
    val ids    = Seq(a, b, c)
    val boxes0 = boxes(canvas, ids)
    val before = canvas.svg
    val thaw   = freezeFrames()
    canvas.state.history.markGesture()
    canvas.state.replaceSourceDetectingFormat(large)
    Waits
      .settle(!(canvas.svg eq before) && canvas.nodeGroup(NodeId("h")).isDefined, timeoutMs = 10000)
      .flatMap(_ => Waits.after(50))
      .map: _ =>
        check(!(canvas.svg eq before), "precondition: the canvas did not draw the new source")
        check(
          canvas.svg.classList.contains(LayoutTransition.transitioningClass),
          "precondition: no layout transition started"
        )
        val boxes1 = boxes(canvas, ids)
        val off = ids.filter: id =>
          val (l0, t0, w0, h0) = boxes0(id)
          val (l1, t1, w1, h1) = boxes1(id)
          math.abs(l1 - l0) > 1.5 || math.abs(t1 - t0) > 1.5 || math.abs(w1 - w0) > 1.5 || math.abs(h1 - h0) > 1.5
        check(
          off.isEmpty,
          s"$what: at frame 0 of the transition, these nodes are not at their old place and size on the screen: " +
            off.map(id => s"$id ${boxes0(id)} -> ${boxes1(id)}").mkString("; ")
        )
      .transform: outcome =>
        thaw()
        outcome

  private def ready(canvas: TestCanvas): Future[Unit] =
    sizeLikeApp(canvas)
    check(canvas.state.animateLayoutChanges.now(), "precondition: the layout animation is off")
    check(!LayoutTransition.reducedMotion, "precondition: the test browser asks for reduced motion")
    // The first drawing and the fit after it.
    Waits.after(600)

  test("with Auto on, frame 0 of the transition shows each old node at its old place and size"):
    withCanvas(small, a): canvas =>
      ready(canvas).flatMap: _ =>
        check(canvas.state.autoFit.now(), "precondition: Auto is off")
        checkFrameZero(canvas, "Auto on")

  test("with Auto off, frame 0 of the transition shows each old node at its old place and size"):
    withCanvas(small, a): canvas =>
      ready(canvas).flatMap: _ =>
        canvas.state.autoFit.set(false)
        // The selected node is the focal node of the anchor.
        canvas.state.selection.set(ElementIds.from(b))
        Waits.after(30).flatMap(_ => checkFrameZero(canvas, "Auto off"))
