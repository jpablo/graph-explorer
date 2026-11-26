package org.jpablo.graphexplorer.viewer.components.svgCanvas

import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.viewer.models.{ElementIds, NodeId}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/** Fit, Fit width and Fit height, and their keys: f, w and ⇧H.
  *
  * Each fit leaves a margin of 8 px between the drawing and the edges of the SVG box. Fit shows all of the drawing
  * as large as the box minus the margins allows, at the center. Fit width makes the width of the drawing the width of
  * the box minus the two margins. Fit height does the same for the height. When the other axis of the drawing is then
  * larger than the box, the start of the drawing shows: the top after Fit width, the left side after Fit height, at
  * the margin.
  *
  * The tests measure the drawing on the screen, not the viewBox. The drawing has no background polygon of Graphviz.
  *
  * Fit width and Fit height set Auto to off. Else the next layout fits all of the drawing again.
  */
class FitAxisSpec extends FunSuite with TestHelpers with LoggedChecks:

  import FitTestCanvas.{canvasH, canvasW, margin, near, Page}

  def checkPrefix = "FitAxisSpec"

  // Through the Scala.js test bridge and the sbt client, a munit failure arrives with no message. An AssertionError
  // keeps its message.
  override def check(condition: Boolean, clue: => String): Unit =
    if !condition then throw AssertionError(s"[$checkPrefix] $clue")

  override def munitFixtures = List(mockStorageFixture())

  /** A chain of 14 nodes from the top down: much taller than wide. */
  private val tall = (1 to 14).map(i => s"n$i").mkString("digraph G { ", " -> ", " }")

  /** The same chain from left to right: much wider than tall. */
  private val wide = (1 to 14).map(i => s"n$i").mkString("digraph G { rankdir=LR; ", " -> ", " }")

  private def withCanvas(id: String, source: String)(body: Page => Future[Unit]): Future[Unit] =
    // The empty graph (8 x 8) shows first. The drawing is ready when it has the 14 nodes of the chain.
    withGraphvizAsync(FitTestCanvas.withCanvas(_, id, source)(FitTestCanvas.dotNodes(14))(body))

  test("w fits the width of a tall drawing, and shows its top"):
    withCanvas("fit-axis-width", tall): p =>
      p.key("w")
      afterMicrotasks:
        val d = p.drawn
        check(near(d.width, canvasW - 2 * margin), s"after w the drawing is not the canvas width minus 16 px: $d")
        check(near(d.left, margin), s"after w the drawing is not centered across: $d")
        check(near(d.top, margin), s"after w the top of the drawing is not at the margin: $d")
        check(d.height > canvasH, s"precondition: the drawing is not taller than the canvas: $d")
        check(!p.state.autoFit.now(), "Fit width did not set Auto to off")

  test("⇧H fits the height of a wide drawing, and shows its left side"):
    withCanvas("fit-axis-height", wide): p =>
      p.key("H", shift = true)
      afterMicrotasks:
        val d = p.drawn
        check(near(d.height, canvasH - 2 * margin), s"after ⇧H the drawing is not the canvas height minus 16 px: $d")
        check(near(d.top, margin), s"after ⇧H the drawing is not centered from top to bottom: $d")
        check(near(d.left, margin), s"after ⇧H the left side of the drawing is not at the margin: $d")
        check(d.width > canvasW, s"precondition: the drawing is not wider than the canvas: $d")
        check(!p.state.autoFit.now(), "Fit height did not set Auto to off")

  test("w on a wide drawing is the same as Fit"):
    withCanvas("fit-axis-width-wide", wide): p =>
      p.key("w")
      afterMicrotasks:
        val d = p.drawn
        check(near(d.width, canvasW - 2 * margin), s"after w the drawing is not the canvas width minus 16 px: $d")
        check(near(d.left, margin), s"after w the drawing is not centered across: $d")
        check(near(d.top, d.bottom), s"after w a short drawing is not centered: $d")

  test("f fits all of the drawing again after w"):
    withCanvas("fit-axis-page", tall): p =>
      p.key("w")
      afterMicrotasks(p.key("f"))
        .flatMap(_ => afterMicrotasks(p.drawn))
        .map: d =>
          check(near(d.height, canvasH - 2 * margin), s"after f the tall drawing is not the canvas height minus 16 px: $d")
          check(near(d.top, margin), s"after f the top of the tall drawing is not at the margin: $d")
          check(near(d.left, d.right), s"after f the drawing is not centered across: $d")

  test("f fits a wide drawing with the margin at the sides, and centers it from top to bottom"):
    withCanvas("fit-axis-page-wide", wide): p =>
      p.key("H", shift = true)
      afterMicrotasks(p.key("f"))
        .flatMap(_ => afterMicrotasks(p.drawn))
        .map: d =>
          check(near(d.width, canvasW - 2 * margin), s"after f the wide drawing is not the canvas width minus 16 px: $d")
          check(near(d.left, margin), s"after f the left side of the wide drawing is not at the margin: $d")
          check(near(d.top, d.bottom), s"after f the drawing is not centered from top to bottom: $d")
          check(d.top > margin, s"precondition: the wide drawing is not limited by the width: $d")

  test("a diagram opens with the frame of f (Auto fits the new drawing)"):
    withCanvas("fit-axis-open", tall): p =>
      afterMicrotasks:
        val d = p.drawn
        check(p.state.autoFit.now(), "precondition: Auto is off when the diagram opens")
        check(near(d.height, canvasH - 2 * margin), s"after the open the drawing is not the canvas height minus 16 px: $d")
        check(near(d.top, margin), s"after the open the top of the drawing is not at the margin: $d")
        check(near(d.left, d.right), s"after the open the drawing is not centered across: $d")

  test("h still hides the selection, and ⇧H does not hide it"):
    withCanvas("fit-axis-hide", tall): p =>
      val n1 = NodeId("n1")
      p.state.selection.set(ElementIds(Set(n1)))
      p.key("H", shift = true)
      check(!p.state.metadata.hiddenElements.now().contains(n1), "⇧H hid the selection")
      p.key("h")
      check(p.state.metadata.hiddenElements.now().contains(n1), "h did not hide the selection")
      Future.unit

end FitAxisSpec
