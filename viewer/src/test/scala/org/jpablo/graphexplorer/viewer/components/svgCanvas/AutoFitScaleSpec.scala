package org.jpablo.graphexplorer.viewer.components.svgCanvas

import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidBackend
import org.jpablo.graphexplorer.viewer.models.NodeId
import org.jpablo.graphexplorer.viewer.utils.{TestHelpers, Waits}
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/** The frame of Auto and of Fit in an automatic diagram.
  *
  * Found on 2026-11-27. With Auto on, one node of an automatic diagram filled most of the canvas, and two nodes had
  * almost no space above and below them. The cause:
  *
  *   - Auto zoomed in past the natural scale. The limit of `autoFitOf` was only for a free layout.
  *   - The fit kept 8 px from each edge of the svg box. The zoom toolbar floats over the bottom of the canvas, so it
  *     covered the bottom of the drawing.
  *
  * Now:
  *
  *   - With Auto on, the fit does not zoom in past the natural scale in each diagram. The natural scale of a Graphviz
  *     drawing is 4/3 px for one point. The natural scale of a Mermaid drawing is 1 px for one unit (Mermaid draws in
  *     px).
  *   - Each fit (Auto, Fit, Fit width, Fit height) keeps [[AutoFitScaleSpec.SideMargin]] px from the top, the left
  *     and the right of the svg box, and [[AutoFitScaleSpec.BottomMargin]] px from the bottom. The bottom margin
  *     keeps the drawing above the zoom toolbar. A drawing that fits goes to the center of the space inside the
  *     margins.
  *   - Fit (the button) has no limit of the zoom.
  */
class AutoFitScaleSpec extends FunSuite with TestHelpers with LoggedChecks with CanvasFixture:
  import AutoFitScaleSpec.*
  import NewNodePlacementSpec.{centerOf, pxPerPoint, shapeOf, sizeLikeApp}

  def checkPrefix = "AutoFitScaleSpec"

  // Through the Scala.js test bridge and the sbt client, a munit failure arrives with no message. An AssertionError
  // keeps its message.
  override def check(condition: Boolean, clue: => String): Unit =
    if !condition then throw AssertionError(s"[$checkPrefix] $clue")

  def canvasDiagramId = "auto-fit-scale-spec"

  override def munitFixtures = List(mockStorageFixture())

  private val a = NodeId("a")
  private val b = NodeId("b")

  /** A chain of 14 nodes from the top down: much taller than the canvas at the natural scale. */
  private val tall = (1 to 14).map(i => s"n$i").mkString("digraph G { ", " -> ", " }")

  /** Show `source` in a canvas of the size of the app, with Auto on, and run `body` after the render.
    *
    * The canvas first shows a different diagram. Then the size of the svg changes to the size in the app (see
    * `sizeLikeApp`), and an edit of the source draws `source`. Auto fits that new drawing in the canvas of the app.
    */
  private def withAutoCanvasWhen(source: String)(ready: TestCanvas => Boolean)(body: TestCanvas => Future[Unit]): Future[Unit] =
    withCanvas("digraph G { z }", NodeId("z")): canvas =>
      sizeLikeApp(canvas.box)
      canvas.state.animateLayoutChanges.set(false)
      Waits.after(50).flatMap: _ =>
        check(canvas.state.autoFit.now(), "precondition: Auto is off when the diagram opens")
        val before = canvas.svg
        canvas.state.replaceSourceDetectingFormat(source)
        Waits
          .settle(!(canvas.svg eq before) && ready(canvas), timeoutMs = 10000)
          .flatMap(_ => Waits.after(50))
          .flatMap: _ =>
            check(!(canvas.svg eq before) && ready(canvas), "precondition: the canvas did not draw the new source")
            check(canvas.state.autoFit.now(), "precondition: the edit of the source turned Auto off")
            check(!canvas.state.drawsFreeLayoutNow, "precondition: the diagram is a free layout")
            body(canvas)

  private def withAutoCanvas(source: String, first: NodeId)(body: TestCanvas => Future[Unit]): Future[Unit] =
    withAutoCanvasWhen(source)(_.nodeGroup(first).isDefined)(body)

  /** The node `id` shows at the natural scale of Graphviz. */
  private def atNaturalScale(canvas: TestCanvas, id: NodeId): Unit =
    val scale = pxPerPoint(canvas.nodeGroup(id).get)
    check(
      math.abs(scale / NaturalPxPerPoint - 1) < 0.02,
      f"with Auto on, the node $id shows at $scale%.2f px for one point, and the natural scale is $NaturalPxPerPoint%.2f"
    )

  /** The client box of the marks of the Graphviz nodes of the canvas. */
  private def nodesBox(canvas: TestCanvas): (Double, Double, Double, Double) =
    val boxes = canvas.box.querySelectorAll("g.node").toSeq.map(g => shapeOf(g).getBoundingClientRect())
    (boxes.map(_.left).min, boxes.map(_.top).min, boxes.map(_.right).max, boxes.map(_.bottom).max)

  /** The center of the space inside the margins of the svg box. */
  private def innerCenter(canvas: TestCanvas): (Double, Double) =
    val r = canvas.svg.getBoundingClientRect()
    ((r.left + SideMargin + r.right - SideMargin) / 2, (r.top + SideMargin + r.bottom - BottomMargin) / 2)

  private def atInnerCenter(canvas: TestCanvas, what: String, at: (Double, Double)): Unit =
    val (x0, y0) = innerCenter(canvas)
    check(
      math.abs(at._1 - x0) <= 2 && math.abs(at._2 - y0) <= 2,
      f"$what is at (${at._1}%.1f, ${at._2}%.1f), and the center of the space inside the margins is ($x0%.1f, $y0%.1f)"
    )

  /** The drawing of the nodes keeps the margins from the edges of the svg box. */
  private def insideMargins(canvas: TestCanvas, what: String): Unit =
    val r                         = canvas.svg.getBoundingClientRect()
    val (left, top, right, bottom) = nodesBox(canvas)
    val detail = f"the nodes are at left ${left - r.left}%.1f, top ${top - r.top}%.1f, right ${r.right - right}%.1f, " +
      f"bottom ${r.bottom - bottom}%.1f px from the edges of the svg box"
    check(top - r.top >= SideMargin - 1, s"$what: the top margin is less than $SideMargin px: $detail")
    check(left - r.left >= SideMargin - 1, s"$what: the left margin is less than $SideMargin px: $detail")
    check(r.right - right >= SideMargin - 1, s"$what: the right margin is less than $SideMargin px: $detail")
    check(r.bottom - bottom >= BottomMargin - 1, s"$what: the bottom margin is less than $BottomMargin px: $detail")

  test("with Auto on, an automatic diagram of one node shows it at the natural scale, at the center"):
    withAutoCanvas("digraph G { a }", a): canvas =>
      atNaturalScale(canvas, a)
      atInnerCenter(canvas, "the node a", centerOf(shapeOf(canvas.nodeGroup(a).get)))
      Future.unit

  test("with Auto on, an automatic diagram of two nodes shows them at the natural scale, at the center"):
    withAutoCanvas("digraph G { a -> b }", a): canvas =>
      atNaturalScale(canvas, a)
      atNaturalScale(canvas, b)
      val (left, top, right, bottom) = nodesBox(canvas)
      atInnerCenter(canvas, "the two nodes", ((left + right) / 2, (top + bottom) / 2))
      Future.unit

  test("with Auto on, an automatic Mermaid flowchart of one node shows at the natural scale of Mermaid"):
    withAutoCanvasWhen("flowchart TD\n  A[Start]\n")(cv =>
      cv.box.querySelector(s"svg rect.${MermaidBackend.sheetClass}") != null
    ): canvas =>
      val scale = Option(CanvasRoot.mainGroup(canvas.svg).getScreenCTM()).map(_.a).getOrElse(0.0)
      check(
        math.abs(scale - 1) < 0.02,
        f"with Auto on, the Mermaid drawing shows at $scale%.2f px for one unit, and the natural scale is 1 px"
      )
      Future.unit

  test("with Auto on, a drawing larger than the canvas fits inside the margins, below the natural scale"):
    withAutoCanvas(tall, NodeId("n1")): canvas =>
      val scale = pxPerPoint(canvas.nodeGroup(NodeId("n1")).get)
      check(scale < NaturalPxPerPoint, f"the tall drawing shows at $scale%.2f px for one point, above the natural scale")
      insideMargins(canvas, "after the open")
      // The height limits the fit: the drawing touches the top margin and the bottom margin.
      val r                    = canvas.svg.getBoundingClientRect()
      val (_, top, _, bottom) = nodesBox(canvas)
      check(
        math.abs(top - r.top - SideMargin) <= 2 && math.abs(r.bottom - bottom - BottomMargin) <= 2,
        f"after the open, the tall drawing is not between the margins: top ${top - r.top}%.1f, bottom ${r.bottom - bottom}%.1f"
      )
      Future.unit

  test("Fit (the button) zooms in past the natural scale, and keeps the margins"):
    withAutoCanvas("digraph G { a -> b }", a): canvas =>
      canvas.state.resetView()
      Waits.after(50).map: _ =>
        val scale = pxPerPoint(canvas.nodeGroup(a).get)
        check(scale > NaturalPxPerPoint * 1.5, f"Fit shows the two nodes at $scale%.2f px for one point: Fit has a limit")
        insideMargins(canvas, "after Fit")
        val r                    = canvas.svg.getBoundingClientRect()
        val (_, top, _, bottom) = nodesBox(canvas)
        check(
          math.abs(top - r.top - SideMargin) <= 2 && math.abs(r.bottom - bottom - BottomMargin) <= 2,
          f"after Fit, the drawing is not between the margins: top ${top - r.top}%.1f, bottom ${r.bottom - bottom}%.1f"
        )

end AutoFitScaleSpec

object AutoFitScaleSpec:

  /** The natural scale of Graphviz: one point is 4/3 CSS px. */
  val NaturalPxPerPoint = 96.0 / 72.0

  /** The margin of a fit at the top, the left and the right of the svg box, in px. */
  val SideMargin = 32.0

  /** The margin of a fit at the bottom of the svg box, in px. The zoom toolbar floats there: it is 40 px high, 8 px
    * above the bottom of the canvas.
    */
  val BottomMargin = 56.0
