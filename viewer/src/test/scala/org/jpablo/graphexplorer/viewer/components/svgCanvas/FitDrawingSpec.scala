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
  * and without the background polygon) 8 px from the edges of the SVG box, on the fitted axis.
  */
class FitDrawingSpec extends FunSuite with TestHelpers with LoggedChecks:

  import FitTestCanvas.{canvasH, canvasW, margin, near, Drawn, Page}

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

  /** After f: all of the drawing at the center, with the margin on the axis that limits it. */
  private def checkPage(name: String, d: Drawn): Unit =
    check(d.left >= margin - 1 && d.top >= margin - 1, s"$name: after f a part of the drawing is in the margin: $d")
    check(near(d.left, d.right), s"$name: after f the drawing is not centered across: $d")
    check(near(d.top, d.bottom), s"$name: after f the drawing is not centered from top to bottom: $d")
    check(near(d.left min d.top, margin), s"$name: after f the drawing does not touch the 8 px margin: $d")

  /** After w: the width of the drawing is the width of the box minus the margins. */
  private def checkWidth(name: String, d: Drawn): Unit =
    check(near(d.width, canvasW - 2 * margin), s"$name: after w the drawing is not the canvas width minus 16 px: $d")
    check(near(d.left, margin), s"$name: after w the drawing is not centered across: $d")
    if d.height > canvasH - 2 * margin then
      check(near(d.top, margin), s"$name: after w the top of the tall drawing is not at the margin: $d")
    else check(near(d.top, d.bottom), s"$name: after w the short drawing is not centered from top to bottom: $d")

  /** After ⇧H: the height of the drawing is the height of the box minus the margins. */
  private def checkHeight(name: String, d: Drawn): Unit =
    check(near(d.height, canvasH - 2 * margin), s"$name: after ⇧H the drawing is not the canvas height minus 16 px: $d")
    check(near(d.top, margin), s"$name: after ⇧H the drawing is not centered from top to bottom: $d")
    if d.width > canvasW - 2 * margin then
      check(near(d.left, margin), s"$name: after ⇧H the left side of the wide drawing is not at the margin: $d")
    else check(near(d.left, d.right), s"$name: after ⇧H the narrow drawing is not centered across: $d")

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
