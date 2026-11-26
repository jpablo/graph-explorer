package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.render
import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.command.SourceCommand
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.components.{Commands, RouterCommands}
import org.jpablo.graphexplorer.viewer.models.{ElementId, ElementIds, NodeId}
import org.jpablo.graphexplorer.viewer.state.{ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.{TestHelpers, Waits}
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** The selection marks of a node fit the node after a change of its shape.
  *
  * Bug: a selected Mermaid node changed from a wide box to a circle. The circle was 215 units wide, but the marks went
  * 85 screen px past it on each side. `SelectionCasing` measures the stroke of the node's own shape. The shape of a
  * Mermaid circle is a `circle`, which its selector did not match, so it measured the casing rect of the selection.
  * The app stylesheet gives that rect the width that `SelectionCasing` writes. Thus each forced refit (the mount, the
  * end of the layout transition, a resize) made the casing wider. The rules below copy that part of the stylesheet.
  */
class SelectionAfterShapeSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "SelectionAfterShapeSpec"

  // Through the Scala.js test bridge and the sbt client, a munit failure arrives with no message. An AssertionError
  // keeps its message.
  override def check(condition: Boolean, clue: => String): Unit =
    if !condition then throw AssertionError(s"[$checkPrefix] $clue")

  override def munitFixtures = List(mockStorageFixture())

  private val api = NodeId("api")

  private def sleep(ms: Int): Future[Unit] =
    val p = Promise[Unit]()
    js.timers.setTimeout(ms)(p.success(()))
    p.future

  /** The box (x, y, width, height) of the node `api`, without its selection marks, in the user units of the node. */
  private def ownBox(node: dom.Element): Seq[Double] =
    val marks = node.querySelectorAll(".selected-border, .selection-casing")
    (0 until marks.length).foreach(i => marks(i).asInstanceOf[dom.Element].setAttribute("display", "none"))
    val b = node.asInstanceOf[js.Dynamic].getBBox().asInstanceOf[dom.SVGRect]
    (0 until marks.length).foreach(i => marks(i).asInstanceOf[dom.Element].removeAttribute("display"))
    Seq(b.x, b.y, b.width, b.height)

  private def markBox(node: dom.Element): Option[Seq[Double]] =
    Option(node.querySelector("rect.selected-border")).map: r =>
      Seq("x", "y", "width", "height").map(a => r.getAttribute(a).toDouble)

  /** The planned overhang of the marks past the node box is a few screen px (see `SelectionCasing.size`). This is the
    * maximum that the test accepts, in screen px per side.
    */
  private val maxOverhangPx = 10.0

  /** Client px per user unit of the node. */
  private def pxPerUnit(node: dom.Element): Double =
    math.abs(node.asInstanceOf[js.Dynamic].getScreenCTM().a.asInstanceOf[Double])

  /** The overhang of the marks past the node box on each side (left, top, right, bottom), in screen px. */
  private def overhangPx(node: dom.Element, mark: Seq[Double], own: Seq[Double]): Seq[Double] =
    val k = pxPerUnit(node)
    Seq(
      own(0) - mark(0),
      own(1) - mark(1),
      (mark(0) + mark(2)) - (own(0) + own(2)),
      (mark(1) + mark(3)) - (own(1) + own(3))
    ).map(_ * k)

  private def fits(node: dom.Element, mark: Seq[Double], own: Seq[Double]) =
    overhangPx(node, mark, own).forall(o => o >= 0 && o <= maxOverhangPx)

  private def shapeChangeKeepsFit(id: String, source: String): Future[Unit] =
    val startHref = dom.window.location.href
    val owner     = ManualOwner()
    withGraphvizAsync: graphviz =>
      val state    = ViewerState(ViewTarget.diagram(id), graphviz, _ => (), initialSource = Some(source))(using owner)
      val commands = Commands(state, RouterCommands(Router()))
      val box      = dom.document.createElement("div").asInstanceOf[dom.html.Element]
      box.style.width = "800px"
      box.style.height = "600px"
      // The app stylesheet gives the selection marks a stroke width from the variables that `SelectionCasing` writes,
      // with `vector-effect: non-scaling-stroke` (see style.scss). The test page has no app stylesheet, so these rules
      // copy the part that the measure of the marks reads. The selector has an id, as in the app: then the rule wins
      // over the `.node rect` rule in the style of a Mermaid drawing.
      val rules = dom.document.createElement("style")
      rules.textContent =
        "#canvas-container svg.graphviz .selection-casing " +
          "{ stroke-width: var(--gx-node-casing-w, 7px); vector-effect: non-scaling-stroke; } " +
          "#canvas-container svg.graphviz .selected-border " +
          "{ stroke-width: var(--gx-ring-w, 2px); vector-effect: non-scaling-stroke; }"
      box.appendChild(rules)
      dom.document.body.appendChild(box)
      val root = render(box, CanvasContainer(state, commands))
      def drawn = box.querySelectorAll("svg g.node").length == 3
      // The canvas gives the selected node the class `selected`.
      def node = Option(box.querySelector("svg g.node.selected"))
      Waits
        .settle(drawn, timeoutMs = 10000)
        .flatMap(_ => sleep(500))
        .flatMap: _ =>
          check(drawn, s"$id: precondition: the canvas does not show the 3 nodes")
          state.selection.set(ElementIds(Set(api)))
          Waits.settle(node.flatMap(markBox).isDefined)
        .flatMap: _ =>
          check(node.flatMap(markBox).isDefined, s"$id: precondition: the selected node has no selection marks")
          state.runSourceCommand(SourceCommand.SetAttribute(Set[ElementId](api), "shape", "circle"))
          // The new layout, then the transition of 300 ms, then a margin.
          Waits.settle(state.sourceText.now().contains("circle") || state.sourceText.now().contains("((")).flatMap(_ => sleep(1200))
        .map: _ =>
          val n = node.getOrElse(throw AssertionError(s"[$checkPrefix] $id: the node api is gone"))
          check(state.selection.now().contains(api), s"$id: the shape change cleared the selection")
          val own  = ownBox(n)
          val mark = markBox(n)
          check(mark.isDefined, s"$id: the node has no selection marks after the shape change")
          check(
            mark.exists(fits(n, _, own)),
            s"$id: the selection marks do not fit the node: marks ${mark.map(_.map(_.round))}, node ${own.map(_.round)}, " +
              s"overhang px ${mark.map(overhangPx(n, _, own).map(_.round))} (max $maxOverhangPx)\n" +
              state.sourceText.now()
          )
        .transform: outcome =>
          root.unmount()
          box.parentNode.removeChild(box)
          owner.killSubscriptions()
          dom.window.history.replaceState(null, "", startHref)
          outcome

  test("Mermaid: the selection fits a node after a change from a wide box to a circle"):
    shapeChangeKeepsFit(
      "selection-shape-mermaid",
      """flowchart LR
        |  user["User"] --> api["Mother renderer and embedded browser view"]
        |  api --> db["DB"]""".stripMargin
    )

  test("DOT: the selection fits a node after a change from a wide box to a circle"):
    shapeChangeKeepsFit(
      "selection-shape-dot",
      """digraph G { rankdir=LR; user -> api -> db; api [shape=box, label="Mother renderer and embedded browser view"] }"""
    )

end SelectionAfterShapeSpec
