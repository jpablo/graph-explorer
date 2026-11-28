package org.jpablo.graphexplorer.viewer.components.svgCanvas

import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/** A fit uses the drawing, not the padding around it.
  *
  * Mermaid pads its viewBox around the drawing, and a sequence diagram has large margins. The app puts a sheet
  * (`rect.gx-sheet`) on all of the viewBox. Graphviz draws a background polygon on the graph and its `pad`. Before,
  * a fit used the viewBox, and the padding took room on the screen. Now f, w and ⇧H put the drawing (without the sheet
  * and without the background polygon) at the margins of a fit, on the fitted axis: 32 px from the top, the left and
  * the right edge of the SVG box, and 56 px from the bottom edge.
  */
class FitDrawingSpec extends FunSuite with TestHelpers with LoggedChecks:

  import FitTestCanvas.{bottomMargin, innerH, innerW, near, sideMargin, Drawn, Page}

  def checkPrefix = "FitDrawingSpec"

  // Through the Scala.js test bridge and the sbt client, a munit failure arrives with no message. An AssertionError
  // keeps its message.
  override def check(condition: Boolean, clue: => String): Unit =
    if !condition then throw AssertionError(s"[$checkPrefix] $clue")

  override def munitFixtures = List(mockStorageFixture())

  private val sequence =
    """sequenceDiagram
      |  participant A as systemd
      |  participant B as updater
      |  participant C as podman
      |  Note left of A: a long note at the left of the first actor
      |  A->>B: start service
      |  B->>C: start db container
      |  C-->>A: ok""".stripMargin

  private val flowchart =
    """flowchart TD
      |  A["first node"] --> B["second node"] --> C["third node"]
      |  A --> C""".stripMargin

  /** A large `pad` (1 inch) makes the background polygon much larger than the drawing. */
  private val dot =
    """digraph G {
      |  pad=1
      |  a -> b -> c
      |  a -> c
      |}""".stripMargin

  private def withCanvas(id: String, source: String, ready: dom.html.Element => Boolean)(
      body: Page => Future[Unit]
  ): Future[Unit] =
    withGraphvizAsync(FitTestCanvas.withCanvas(_, id, source)(ready)(body))

  /** After f: all of the drawing at the center of the space inside the margins, with the margins on the axis that
    * limits it.
    */
  private def checkPage(name: String, d: Drawn): Unit =
    check(
      d.left >= sideMargin - 1 && d.top >= sideMargin - 1 && d.bottom >= bottomMargin - 1,
      s"$name: after f a part of the drawing is in the margin: $d"
    )
    check(d.centeredAcross, s"$name: after f the drawing is not centered across: $d")
    check(d.centeredDown, s"$name: after f the drawing is not centered from top to bottom: $d")
    check(near(d.left, sideMargin) || near(d.top, sideMargin), s"$name: after f the drawing does not touch the margins: $d")

  /** After w: the width of the drawing is the width of the space inside the margins. */
  private def checkWidth(name: String, d: Drawn): Unit =
    check(near(d.width, innerW), s"$name: after w the drawing is not the canvas width minus the two side margins: $d")
    check(near(d.left, sideMargin), s"$name: after w the drawing is not centered across: $d")
    if d.height > innerH then
      check(near(d.top, sideMargin), s"$name: after w the top of the tall drawing is not at the margin: $d")
    else check(d.centeredDown, s"$name: after w the short drawing is not centered from top to bottom: $d")

  /** After ⇧H: the height of the drawing is the height of the space inside the margins. */
  private def checkHeight(name: String, d: Drawn): Unit =
    check(
      near(d.height, innerH),
      s"$name: after ⇧H the drawing is not the canvas height minus the top and bottom margins: $d"
    )
    check(near(d.top, sideMargin), s"$name: after ⇧H the drawing is not centered from top to bottom: $d")
    if d.width > innerW then
      check(near(d.left, sideMargin), s"$name: after ⇧H the left side of the wide drawing is not at the margin: $d")
    else check(d.centeredAcross, s"$name: after ⇧H the narrow drawing is not centered across: $d")

  /** Press f, w, ⇧H and f again. Check the drawing after each key. */
  private def allFits(name: String, source: String, ready: dom.html.Element => Boolean): Future[Unit] =
    withCanvas(s"fit-drawing-$name", source, ready): p =>
      def after(k: String, shift: Boolean = false): Future[Drawn] =
        afterMicrotasks(p.key(k, shift)).flatMap(_ => afterMicrotasks(p.drawn))
      for
        page   <- after("f")
        _       = checkPage(name, page)
        width  <- after("w")
        _       = checkWidth(name, width)
        height <- after("H", shift = true)
        _       = checkHeight(name, height)
        again  <- after("f")
      yield checkPage(s"$name (again)", again)

  test("f, w and ⇧H fit the drawing of a Mermaid sequence diagram, not its padded viewBox"):
    allFits("sequence", sequence, FitTestCanvas.mermaidShown)

  test("f, w and ⇧H fit the drawing of a Mermaid flowchart, not its padded viewBox"):
    allFits("flowchart", flowchart, FitTestCanvas.mermaidShown)

  test("f, w and ⇧H fit the drawing of a DOT diagram, not its background polygon and pad"):
    allFits("dot", dot, FitTestCanvas.dotNodes(3))

end FitDrawingSpec
