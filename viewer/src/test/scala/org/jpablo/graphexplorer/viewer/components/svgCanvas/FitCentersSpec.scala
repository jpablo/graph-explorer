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

/** Fit puts the center of the drawing at the center of the canvas.
  *
  * Fit sets the zoom to 0.9. The group of the drawing scales around its
  * `transform-origin`. Only the root group of Graphviz had an origin near the
  * center (a rule in style.scss). A Mermaid drawing scaled around the point
  * (0, 0) of its user space, so Fit moved it up and to the left, by 5% of its
  * size. The test page has no app stylesheet, so these tests need an origin
  * that the code sets.
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

  /** The offset (x, y) in px from the center of the canvas to the center of the viewBox on the screen. */
  private def centerOffset(box: dom.html.Element): (Double, Double) =
    val container = box.querySelector("#canvas-container").getBoundingClientRect()
    val svg       = box.querySelector("svg").asInstanceOf[dom.svg.SVG]
    val group     = CanvasRoot.mainGroup(svg)
    val vb        = svg.viewBox.baseVal
    val m         = group.getScreenCTM()
    def screen(x: Double, y: Double) =
      val p = svg.createSVGPoint()
      p.x = x
      p.y = y
      p.matrixTransform(m)
    val a = screen(vb.x, vb.y)
    val b = screen(vb.x + vb.width, vb.y + vb.height)
    (
      (a.x + b.x) / 2 - (container.left + container.width / 2),
      (a.y + b.y) / 2 - (container.top + container.height / 2)
    )

  private def fitCenters(id: String, source: String): Future[Unit] =
    val startHref = dom.window.location.href
    val owner     = ManualOwner()
    withGraphvizAsync: graphviz =>
      val state    = ViewerState(ViewTarget.diagram(id), graphviz, _ => (), initialSource = Some(source))(using owner)
      val commands = Commands(state, RouterCommands(Router()))
      val box      = dom.document.createElement("div").asInstanceOf[dom.html.Element]
      box.style.width = "800px"
      box.style.height = "600px"
      // The app stylesheet makes each floating toolbar `absolute`. Without
      // that rule, the "View-only" chip of a sequence diagram is a block
      // below the SVG, and it makes the canvas container 18 px higher than
      // the SVG. Thus this one rule of the app comes into the test page.
      val floatRule = dom.document.createElement("style")
      floatRule.textContent = ".floating-toolbar { position: absolute; }"
      box.appendChild(floatRule)
      dom.document.body.appendChild(box)
      val root = render(box, CanvasContainer(state, commands))
      def drawn = Option(box.querySelector("svg")).exists(s => s.querySelector("g") != null && s.getAttribute("viewBox") != null)
      Waits
        .settle(drawn, timeoutMs = 10000)
        .flatMap: _ =>
          check(drawn, s"$id: precondition: the canvas shows no drawing")
          state.resetView()
          afterMicrotasks:
            val vb      = box.querySelector("svg").asInstanceOf[dom.svg.SVG].viewBox.baseVal
            val (dx, dy) = centerOffset(box)
            check(
              dx.abs <= 1.0 && dy.abs <= 1.0,
              f"$id: after Fit the center of the drawing is ($dx%.1f, $dy%.1f) px from the center of the canvas " +
                f"(viewBox ${vb.x}%.1f ${vb.y}%.1f ${vb.width}%.1f ${vb.height}%.1f)"
            )
        .transform: outcome =>
          root.unmount()
          box.parentNode.removeChild(box)
          owner.killSubscriptions()
          dom.window.history.replaceState(null, "", startHref)
          outcome

  test("Fit centers a Mermaid sequence diagram whose viewBox does not start at (0, 0)"):
    fitCenters("fit-center-sequence", sequence)

  test("Fit centers a Mermaid flowchart"):
    fitCenters("fit-center-flowchart", flowchart)

  test("Fit centers a DOT diagram"):
    fitCenters("fit-center-dot", dot)
