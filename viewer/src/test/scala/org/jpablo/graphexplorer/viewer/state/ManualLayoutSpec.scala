package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.ownership.{ManualOwner, Owner}
import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.{DiagramLayout, LayoutPoint}
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.viewer.components.selection.GraphvizSelectionStrategy
import org.jpablo.graphexplorer.viewer.models.NodeId
import org.jpablo.graphexplorer.viewer.utils.{TestHelpers, Waits}
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/** Manual layout, part 1 (ADR 0004).
  *
  * A node with a stored position stays there, and Graphviz places each other
  * node around it. Freeze stores the current place of each node, and Arrange
  * removes each stored position. The positions are not in the source.
  *
  * These tests use the real Graphviz and the real Mermaid in the browser. They
  * read the place of each node from the drawn SVG.
  */
class ManualLayoutSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "ManualLayoutSpec"

  // Through the Scala.js test bridge and the sbt client, a munit failure arrives with no message. An AssertionError
  // keeps its message.
  override def check(condition: Boolean, clue: => String): Unit =
    if !condition then throw AssertionError(s"[$checkPrefix] $clue")

  override def munitFixtures = List(mockStorageFixture())

  private val dot =
    """digraph G {
      |  subgraph cluster_g { label="Group"; a; b }
      |  a -> b
      |  b -> c
      |  a -> c
      |  c -> d
      |}""".stripMargin

  private val mermaid =
    """flowchart LR
      |  A[Start] --> B[End]
      |  B --> C
      |""".stripMargin

  /** The tolerance of a place, in points. Graphviz writes two decimals. */
  private val tolerance = 0.5

  private final class View(val state: ViewerState)(using Owner):
    val drawing  = state.svgWithPositions.observe
    val renderer = state.currentRenderer.observe
    val autoLabel = state.rendererOption.autoLabel.observe

    def svg: Option[dom.Element] = drawing.now().map(_.svg.ref)
    def svgText: String          = svg.fold("")(_.outerHTML)
    def isGraphviz: Boolean      = svgText.contains("class=\"graph\"")
    def isMermaid: Boolean       = svgText.contains("aria-roledescription=\"flowchart")
    def nodes: Set[NodeId]       = state.fullGraphNow().nodeIds
    def layout: DiagramLayout    = state.layout.now()
    def gesture(): Unit          = state.history.markGesture()

    /** The center of each node of a Graphviz drawing, in points, with the y axis up. */
    def centers: Map[NodeId, LayoutPoint] =
      svg.toSeq
        .flatMap(_.querySelectorAll("g.node").toSeq.map(_.asInstanceOf[dom.Element]))
        .map(g => GraphvizSelectionStrategy.extractNodeId(g) -> ManualLayoutSpec.centerOf(g))
        .toMap

  private def withView(name: String, source: String)(body: Owner ?=> View => Future[Unit]): Future[Unit] =
    val owner = ManualOwner()
    TestHelpers.sharedGraphviz
      .flatMap: graphviz =>
        given Owner = owner
        val view = View(ViewerState(ViewTarget.diagram(name), graphviz, initialSource = Some(source)))
        Waits
          .settle(view.nodes.nonEmpty && view.drawing.now().nonEmpty, timeoutMs = 10000)
          .flatMap: _ =>
            check(view.nodes.nonEmpty, "precondition: the diagram did not load")
            check(view.drawing.now().nonEmpty, "precondition: the diagram has no drawing")
            body(view)
      .transform: outcome =>
        owner.killSubscriptions()
        outcome

  /** Each center of `after`, moved so that `anchor` is at its place in `before`, is at its place in `before`. A
    * render can move the whole drawing in the SVG (the box of the graph changes). Thus the check compares the
    * places of the nodes with each other.
    */
  private def samePlaces(before: Map[NodeId, LayoutPoint], after: Map[NodeId, LayoutPoint], anchor: NodeId): Unit =
    check(before.keySet == after.keySet, s"the drawn nodes changed: ${before.keySet} -> ${after.keySet}")
    val dx = before(anchor).x - after(anchor).x
    val dy = before(anchor).y - after(anchor).y
    for (id, p) <- after do
      val b = before(id)
      check(
        math.abs(p.x + dx - b.x) <= tolerance && math.abs(p.y + dy - b.y) <= tolerance,
        s"the node $id moved: before $b, after ${LayoutPoint(p.x + dx, p.y + dy)} (all before: $before, after: $after)"
      )

  // ------------------------------------------------------------ freeze

  test("Freeze stores a position for each node, and the next render keeps each node at its place"):
    withView("layout-freeze", dot): view =>
      val before   = view.centers
      val original = view.svgText
      check(before.keySet == view.nodes, s"precondition: the drawing has each node: $before")
      view.gesture()
      view.state.freezeLayout()
      check(view.layout.nodes.keySet == view.nodes.map(_.value), s"Freeze did not store each node: ${view.layout}")
      // The source does not get the positions.
      same(view.state.sourceText.now(), dot, "Freeze changed the source")
      for
        // The free layout draws the arrows in a different way, so the SVG changes.
        _ <- Waits.settle(view.svgText != original, timeoutMs = 5000)
        _ = check(view.svgText != original, "Freeze did not draw the diagram again")
      yield
        check(view.renderer.now().id == "graphviz", s"the free layout is not drawn by Graphviz: ${view.renderer.now().id}")
        check(view.isGraphviz, view.svgText.take(300))
        // The group stays a box.
        check(view.svg.exists(_.querySelector("g.cluster") != null), "the free layout lost the group")
        samePlaces(before, view.centers, NodeId("a"))

  test("a stored position draws a Mermaid flowchart with Graphviz, with the node at that place"):
    withView("layout-mermaid", mermaid): view =>
      check(view.renderer.now().id == "mermaid", s"precondition: Mermaid draws the source, not ${view.renderer.now().id}")
      view.gesture()
      view.state.placeNodes(Map(NodeId("A") -> LayoutPoint(0, 0), NodeId("B") -> LayoutPoint(300, 200)))
      for _ <- Waits.settle(view.renderer.now().id == "graphviz" && view.isGraphviz, timeoutMs = 5000)
      yield
        check(view.renderer.now().id == "graphviz", s"the renderer is ${view.renderer.now().id}")
        check(view.isGraphviz, view.svgText.take(300))
        check(view.autoLabel.now().contains("Free layout"), s"the Auto option says ${view.autoLabel.now()}")
        val c = view.centers
        check(c.keySet == Set(NodeId("A"), NodeId("B"), NodeId("C")), s"the drawn nodes: $c")
        val dx = c(NodeId("B")).x - c(NodeId("A")).x
        val dy = c(NodeId("B")).y - c(NodeId("A")).y
        check(math.abs(dx - 300) <= tolerance && math.abs(dy - 200) <= tolerance, s"B is not at its place from A: $dx, $dy")
        same(view.state.sourceText.now(), mermaid, "a position changed the source")

  test("Freeze of a Mermaid flowchart stores the place of each node from the Mermaid drawing"):
    withView("layout-freeze-mermaid", mermaid): view =>
      check(view.isMermaid, "precondition: Mermaid draws the source")
      view.gesture()
      view.state.freezeLayout()
      check(view.layout.nodes.keySet == Set("A", "B", "C"), s"Freeze did not store each node: ${view.layout}")
      // The flowchart goes from left to right, so A is left of B, and B is left of C.
      val x = view.layout.nodes.view.mapValues(_.x).toMap
      check(x("A") < x("B") && x("B") < x("C"), s"the places do not follow the Mermaid drawing: ${view.layout}")
      for _ <- Waits.settle(view.isGraphviz, timeoutMs = 5000)
      yield check(view.renderer.now().id == "graphviz", s"the renderer is ${view.renderer.now().id}")

  // ------------------------------------------------------------ arrange

  test("Arrange removes each position, and the diagram draws as before"):
    withView("layout-arrange", dot): view =>
      val original = view.svgText
      view.gesture()
      view.state.freezeLayout()
      for
        _ <- Waits.settle(view.svgText != original, timeoutMs = 5000)
        _ = check(view.svgText != original, "precondition: Freeze did not change the drawing")
        _ = view.gesture()
        _ = view.state.arrangeLayout()
        _ <- Waits.settle(view.svgText == original, timeoutMs = 5000)
      yield
        check(!view.layout.hasPositions, s"Arrange kept positions: ${view.layout}")
        check(view.svgText == original, "after Arrange, the drawing is not the drawing of the automatic layout")

  test("Arrange of a Mermaid flowchart gives the drawing back to Mermaid"):
    withView("layout-arrange-mermaid", mermaid): view =>
      view.gesture()
      view.state.freezeLayout()
      for
        _ <- Waits.settle(view.isGraphviz, timeoutMs = 5000)
        _ = check(view.isGraphviz, "precondition: Freeze draws the flowchart with Graphviz")
        _ = view.gesture()
        _ = view.state.arrangeLayout()
        _ <- Waits.settle(view.isMermaid, timeoutMs = 10000)
      yield
        check(view.renderer.now().id == "mermaid", s"the renderer is ${view.renderer.now().id}")
        check(!view.autoLabel.now().contains("Free layout"), s"the Auto option says ${view.autoLabel.now()}")

  // ------------------------------------------------------------ undo

  test("undo and redo of Freeze and of Arrange"):
    withView("layout-undo", dot): view =>
      view.gesture()
      view.state.freezeLayout()
      val frozen = view.layout
      check(frozen.hasPositions, "precondition: Freeze stored positions")
      check(view.state.history.canUndoNow, "Freeze is not a step of the history")
      view.state.history.undo()
      check(!view.layout.hasPositions, s"undo of Freeze kept positions: ${view.layout}")
      view.state.history.redo()
      same(view.layout, frozen, "redo of Freeze")
      view.gesture()
      view.state.arrangeLayout()
      check(!view.layout.hasPositions, "precondition: Arrange removed the positions")
      view.state.history.undo()
      same(view.layout, frozen, "undo of Arrange")
      view.state.history.redo()
      check(!view.layout.hasPositions, s"redo of Arrange kept positions: ${view.layout}")
      // The source text is the same through each step.
      same(view.state.sourceText.now(), dot, "a layout step changed the source")
      Future.unit

  test("a move of nodes in one gesture is one step"):
    withView("layout-move-step", dot): view =>
      view.gesture()
      view.state.freezeLayout()
      val frozen = view.layout
      view.gesture()
      view.state.moveNodesBy(Set(NodeId("a")), 10, 0)
      view.state.moveNodesBy(Set(NodeId("a")), 10, 5)
      same(view.layout.point("a").map(p => (p.x - frozen.point("a").get.x, p.y - frozen.point("a").get.y)), Some((20.0, 5.0)))
      view.state.history.undo()
      same(view.layout, frozen, "one undo did not revert the whole gesture")
      Future.unit

  // ------------------------------------------------------------ the browser library

  test("a diagram of the browser library keeps its layout after a reload"):
    val first = ManualOwner()
    TestHelpers.sharedGraphviz.flatMap: graphviz =>
      val id     = ViewTarget.diagram("layout-reload")
      val state1 = ViewerState(id, graphviz, initialSource = Some(dot))(using first)
      state1.history.markGesture()
      state1.placeNodes(Map(NodeId("a") -> LayoutPoint(10, 20), NodeId("c") -> LayoutPoint(-5.5, 40)))
      val stored = state1.layout.now()
      check(stored.nodes.keySet == Set("a", "c"), s"precondition: the positions are in the layout: $stored")
      state1.closePersistence()
      first.killSubscriptions()
      val second = ManualOwner()
      val state2 = ViewerState(id, graphviz, initialSource = Some(dot))(using second)
      try
        same(state2.layout.now(), stored, "the layout after a reload")
        check(!state2.sourceText.now().contains("pos="), "the layout went into the source")
        Future.unit
      finally
        state2.closePersistence()
        second.killSubscriptions()

object ManualLayoutSpec:

  /** The center of a node of a Graphviz SVG, with the y axis up. */
  def centerOf(g: dom.Element): LayoutPoint =
    Option(g.querySelector("ellipse")) match
      case Some(e) => LayoutPoint(e.getAttribute("cx").toDouble, -e.getAttribute("cy").toDouble)
      case None =>
        val points = Option(g.querySelector("polygon")).map(_.getAttribute("points")).getOrElse("")
        val xy     = points.trim.split("\\s+").toVector.filter(_.contains(',')).map(_.split(',')).map(a => (a(0).toDouble, a(1).toDouble))
        val xs     = xy.map(_._1)
        val ys     = xy.map(_._2)
        LayoutPoint((xs.min + xs.max) / 2, -(ys.min + ys.max) / 2)
