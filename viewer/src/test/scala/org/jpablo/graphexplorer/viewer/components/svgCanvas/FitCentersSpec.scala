package org.jpablo.graphexplorer.viewer.components.svgCanvas

import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/** Fit puts the center of the drawing at the center of the canvas.
  *
  * The group of the drawing scales around its `transform-origin`. Only the root
  * group of Graphviz had an origin near the center (a rule in style.scss). A
  * Mermaid drawing scaled around the point (0, 0) of its user space, so Fit
  * moved it up and to the left. The test page has no app stylesheet, so these
  * tests need an origin that the code sets.
  *
  * The test measures the drawing on the screen, not the viewBox. Mermaid pads
  * its viewBox, and the padding is not the same on all sides. Thus the center
  * of the viewBox is not the center of the drawing. Fit centers the drawing.
  */
class FitCentersSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "FitCentersSpec"

  override def munitFixtures = List(mockStorageFixture())

  /** A viewBox that does not start at (0, 0): Mermaid puts the notes left of the first actor. */
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
    """flowchart LR
      |  A["first node"] --> B["second node"] --> C["third node"]""".stripMargin

  private val dot =
    """digraph G {
      |  a -> b -> c
      |  a -> c
      |}""".stripMargin

  private def fitCenters(id: String, source: String, ready: dom.html.Element => Boolean): Future[Unit] =
    withGraphvizAsync: graphviz =>
      FitTestCanvas.withCanvas(graphviz, id, source)(ready): p =>
        p.state.resetView()
        afterMicrotasks:
          val d  = p.drawn
          val dx = (d.left - d.right) / 2
          val dy = (d.top - d.bottom) / 2
          check(
            dx.abs <= 1.0 && dy.abs <= 1.0,
            f"$id: after Fit the center of the drawing is ($dx%.1f, $dy%.1f) px from the center of the canvas: $d"
          )

  test("Fit centers a Mermaid sequence diagram whose viewBox does not start at (0, 0)"):
    fitCenters("fit-center-sequence", sequence, FitTestCanvas.mermaidShown)

  test("Fit centers a Mermaid flowchart"):
    fitCenters("fit-center-flowchart", flowchart, FitTestCanvas.mermaidShown)

  test("Fit centers a DOT diagram"):
    fitCenters("fit-center-dot", dot, FitTestCanvas.dotNodes(3))
