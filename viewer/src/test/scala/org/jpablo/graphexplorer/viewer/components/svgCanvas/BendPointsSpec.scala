package org.jpablo.graphexplorer.viewer.components.svgCanvas

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.layout.FreeLayout
import org.jpablo.graphexplorer.gxcore.model.{DiagramLayout, LayoutPoint}
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.components.{Commands, RouterCommands}
import org.jpablo.graphexplorer.viewer.models.{Arrow, ArrowId, ElementIds, NodeId}
import org.jpablo.graphexplorer.viewer.state.mouseActions.MouseAction
import org.jpablo.graphexplorer.viewer.utils.{TestHelpers, Waits}
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/** Manual layout, part 4: the bend points of an arrow (ADR 0004).
  *
  * The tests send real mouse events to the rendered canvas. In a free layout, a selected arrow shows a handle on each
  * bend point. A drag on the path of the arrow adds a bend, a drag of a handle moves it, and a double-click on a handle
  * removes it. The drawing goes through the bends. The bends are in layout points, with the y axis up.
  */
class BendPointsSpec extends FunSuite with TestHelpers with LoggedChecks with CanvasFixture:

  def checkPrefix = "BendPointsSpec"

  // Through the Scala.js test bridge and the sbt client, a munit failure arrives with no message. An AssertionError
  // keeps its message.
  override def check(condition: Boolean, clue: => String): Unit =
    if !condition then throw AssertionError(s"[$checkPrefix] $clue")

  def canvasDiagramId = "bend-points-spec"

  private given CanEqual[ElementIds, ElementIds] = CanEqual.derived

  override def munitFixtures = List(mockStorageFixture())

  private val dot =
    """digraph G {
      |  a -> b
      |  b -> c
      |  a -> c
      |}""".stripMargin

  private val mermaid =
    """flowchart LR
      |  A[Start] --> B[End]
      |  B --> C
      |""".stripMargin

  private val a = NodeId("a")
  private val b = NodeId("b")
  private val c = NodeId("c")

  /** The places of the nodes: `a` on the left, `b` on the right, `c` below `b`. The arrow `a -> b` is straight. */
  private val places = Map(a -> LayoutPoint(0, 0), b -> LayoutPoint(270, 0), c -> LayoutPoint(270, -180))

  /** The spacing of the snap grid, in layout points. */
  private val grid = 9.0

  // ------------------------------------------------------------ events

  private def mouse(kind: String, x: Double, y: Double, alt: Boolean = false, shift: Boolean = false, buttons: Int = 1): dom.MouseEvent =
    val init = new dom.MouseEventInit {}
    init.clientX = x
    init.clientY = y
    init.button = 0
    init.buttons = buttons
    init.altKey = alt
    init.shiftKey = shift
    init.bubbles = true
    init.cancelable = true
    dom.MouseEvent(kind, init)

  /** A press as a browser sends it: `pointerdown` (the history marks a gesture), then `mousedown`. */
  private def press(target: dom.Element, x: Double, y: Double): Unit =
    val init = new dom.PointerEventInit {}
    init.clientX = x
    init.clientY = y
    init.button = 0
    init.buttons = 1
    init.bubbles = true
    target.dispatchEvent(new dom.PointerEvent("pointerdown", init))
    target.dispatchEvent(mouse("mousedown", x, y))

  private def moveTo(canvas: TestCanvas, x: Double, y: Double, alt: Boolean = false): Unit =
    canvas.svg.dispatchEvent(mouse("mousemove", x, y, alt = alt))

  private def release(canvas: TestCanvas, x: Double, y: Double, alt: Boolean = false): Unit =
    canvas.svg.dispatchEvent(mouse("mouseup", x, y, alt = alt, buttons = 0))

  /** A click as a browser sends it, on the element under the point. */
  private def click(target: dom.Element, canvas: TestCanvas, x: Double, y: Double): Unit =
    press(target, x, y)
    target.dispatchEvent(mouse("mouseup", x, y, buttons = 0))
    target.dispatchEvent(mouse("click", x, y, buttons = 0))

  /** Press on `target` at `(x0, y0)`, move by `(dx, dy)` client pixels in four steps, and release. `beforeRelease`
    * runs after the last move.
    */
  private def dragFrom(canvas: TestCanvas, target: dom.Element, x0: Double, y0: Double, dx: Double, dy: Double, alt: Boolean = false)(
      beforeRelease: => Unit = ()
  ): Unit =
    press(target, x0, y0)
    for i <- 1 to 4 do moveTo(canvas, x0 + dx * i / 4, y0 + dy * i / 4, alt)
    beforeRelease
    release(canvas, x0 + dx, y0 + dy, alt)

  private def centerOf(e: dom.Element): (Double, Double) =
    val r = e.getBoundingClientRect()
    (r.left + r.width / 2, r.top + r.height / 2)

  // ------------------------------------------------------------ the drawing

  private def arrowOf(canvas: TestCanvas, s: NodeId, t: NodeId): Arrow =
    val arrow = canvas.state.fullGraphNow().arrows.values.find(x => x.source == s && x.target == t)
    check(arrow.isDefined, s"precondition: the diagram has no arrow $s -> $t")
    arrow.get

  private def keyOf(canvas: TestCanvas, id: ArrowId): String = FreeLayout.arrowKeys(canvas.state.fullGraphNow())(id)

  private def bendsOf(canvas: TestCanvas, id: ArrowId): Vector[LayoutPoint] = canvas.state.layout.now().bends(keyOf(canvas, id))

  private def edgeGroup(canvas: TestCanvas, id: ArrowId): Option[dom.Element] =
    canvas.box.querySelectorAll("g.edge").toSeq.collectFirst { case g: dom.Element if g.id == id.toSvg => g }

  /** The drawn spline of an arrow: its first path that is not the hit halo. */
  private def splineOf(edge: dom.Element): dom.svg.Path =
    edge.querySelector(SelectableElement.splineSelector).asInstanceOf[dom.svg.Path]

  private def mainGroup(canvas: TestCanvas): dom.svg.G = CanvasRoot.mainGroup(canvas.svg)

  /** Client px for one layout point. */
  private def pxPerPoint(canvas: TestCanvas): Double = mainGroup(canvas).getScreenCTM().a

  /** The point of the layout under the client point `(x, y)`, with no snap. */
  private def layoutAt(canvas: TestCanvas, x: Double, y: Double): LayoutPoint =
    val pt = canvas.svg.createSVGPoint()
    pt.x = x
    pt.y = y
    val local    = pt.matrixTransform(mainGroup(canvas).getScreenCTM().inverse())
    val (ox, oy) = canvas.state.layoutOffsetNow()
    LayoutPoint(local.x + ox, -local.y + oy)

  /** The client point of the layout point `p`. */
  private def clientOf(canvas: TestCanvas, p: LayoutPoint): (Double, Double) =
    val (ox, oy) = canvas.state.layoutOffsetNow()
    val pt       = canvas.svg.createSVGPoint()
    pt.x = p.x - ox
    pt.y = -(p.y - oy)
    val client = pt.matrixTransform(mainGroup(canvas).getScreenCTM())
    (client.x, client.y)

  /** The nearest distance, in layout points, from the layout point `p` to the drawn spline of `edge`. The test samples
    * the path with `getPointAtLength`.
    */
  private def distanceToPath(canvas: TestCanvas, edge: dom.Element, p: LayoutPoint): Double =
    val path     = splineOf(edge)
    val total    = path.getTotalLength()
    val (ox, oy) = canvas.state.layoutOffsetNow()
    val (px, py) = (p.x - ox, -(p.y - oy))
    (0 to 400).iterator
      .map: i =>
        val q = path.getPointAtLength(total * i / 400)
        math.hypot(q.x - px, q.y - py)
      .min

  /** The client point at the fraction `f` of the length of the drawn spline of `edge`. */
  private def pointOnPath(edge: dom.Element, f: Double): (Double, Double) =
    val path = splineOf(edge)
    val q    = path.getPointAtLength(path.getTotalLength() * f)
    val pt   = path.ownerSVGElement.createSVGPoint()
    pt.x = q.x
    pt.y = q.y
    val client = pt.matrixTransform(path.getScreenCTM())
    (client.x, client.y)

  /** The shape of the node `id`: the element that a press hits. */
  private def shapeOf(canvas: TestCanvas, id: NodeId): dom.Element =
    val g = canvas.nodeGroup(id)
    check(g.isDefined, s"the canvas does not show the node $id")
    val shape = Option(g.get.querySelector("ellipse")).orElse(Option(g.get.querySelector("polygon")))
    check(shape.isDefined, s"the node $id has no shape: ${g.get.outerHTML.take(300)}")
    shape.get

  private def handles(canvas: TestCanvas): Seq[dom.Element] =
    canvas.box.querySelectorAll("g.gx-bend-handle").toSeq.collect { case e: dom.Element => e }

  private def onGrid(v: Double): Boolean = math.abs(v / grid - math.round(v / grid)) < 1e-6

  private def near(obtained: Double, expected: Double, tolerance: Double, what: String): Unit =
    check(math.abs(obtained - expected) <= tolerance, s"$what: obtained $obtained, expected $expected")

  /** Give the svg the size of the canvas, as the app stylesheet does. */
  private def sizeLikeApp(canvas: TestCanvas): Unit =
    val rules = dom.document.createElement("style")
    rules.textContent =
      ".floating-toolbar { position: absolute; } " +
        "#canvas-container { width: 100%; height: 100%; } " +
        "#canvas-container > svg { display: block; width: 100%; height: 100%; }"
    canvas.box.appendChild(rules)

  /** Wait for a new svg that shows the node `first`, and for the frame after it. */
  private def redrawn(canvas: TestCanvas, before: dom.svg.SVG, first: NodeId): Future[Unit] =
    Waits
      .settle(!(canvas.svg eq before) && canvas.nodeGroup(first).isDefined, timeoutMs = 5000)
      .flatMap(_ => Waits.after(30))
      .map(_ => check(!(canvas.svg eq before), "the canvas did not draw the diagram again"))

  /** Change the layout with `change`, as one gesture, and wait for the new drawing. */
  private def relayout(canvas: TestCanvas, first: NodeId)(change: => Unit): Future[Unit] =
    val before = canvas.svg
    canvas.state.history.markGesture()
    change
    redrawn(canvas, before, first)

  /** A canvas of a free layout with the nodes at [[places]], with no layout animation. */
  private def withFreeCanvas(body: TestCanvas => Future[Unit]): Future[Unit] =
    withCanvas(dot, a): canvas =>
      sizeLikeApp(canvas)
      canvas.state.animateLayoutChanges.set(false)
      relayout(canvas, a)(canvas.state.placeNodes(places)).flatMap(_ => body(canvas))

  /** Select the arrow `id`, and wait for the canvas to show its controls. */
  private def selectArrow(canvas: TestCanvas, id: ArrowId, handleCount: Int): Future[Unit] =
    canvas.state.selection.set2(id)
    Waits
      .settle(handles(canvas).size == handleCount && canvas.box.querySelector("g.edge-endpoint-disk") != null, timeoutMs = 3000)
      .map(_ => check(handles(canvas).size == handleCount, s"the selected arrow shows ${handles(canvas).size} bend handles, expected $handleCount"))

  // ------------------------------------------------------------ an automatic diagram

  test("an automatic diagram shows no bend handles, and a drag on its arrow adds no bend"):
    withCanvas(dot, a): canvas =>
      val ab = arrowOf(canvas, a, b)
      // The svg of this canvas is 800 x 1690 px, taller than the window. Auto shows the small drawing at the natural
      // scale, at the center of the svg, below the window. Fit (no limit of the zoom) fills the svg with the drawing,
      // so the arrow shows in the window.
      canvas.state.resetView()
      canvas.state.selection.set2(ab.id)
      Waits.settle(canvas.box.querySelector("g.edge-endpoint-disk") != null, timeoutMs = 3000).flatMap(_ => Waits.after(50)).map: _ =>
        val (px, py) = pointOnPath(edgeGroup(canvas, ab.id).get, 0.5)
        check(dom.document.elementFromPoint(px, py) != null, s"precondition: the arrow at ($px, $py) is not in the window")
        check(handles(canvas).isEmpty, s"an automatic diagram shows bend handles: ${handles(canvas).size}")
        val edge     = edgeGroup(canvas, ab.id).get
        val (x0, y0) = pointOnPath(edge, 0.5)
        dragFrom(canvas, dom.document.elementFromPoint(x0, y0), x0, y0, 0, -50)()
        same(canvas.state.layout.now(), DiagramLayout.empty, "a drag on an arrow of an automatic diagram changed the layout")

  test("a locked diagram takes a change of a bend and a reset of the route, because the layout is not the source"):
    withFreeCanvas: canvas =>
      val ab = arrowOf(canvas, a, b)
      canvas.state.metadata.locked.set(true)
      val text0 = canvas.state.sourceText.now()
      canvas.state.history.markGesture()
      canvas.state.setArrowBends(ab.id, Vector(LayoutPoint(135, 90)))
      same(bendsOf(canvas, ab.id), Vector(LayoutPoint(135, 90)), "a locked diagram refused the bend")
      canvas.state.history.markGesture()
      canvas.state.resetArrowRoutes(Set(ab.id))
      same(bendsOf(canvas, ab.id), Vector.empty[LayoutPoint], "a locked diagram refused the reset of the route")
      same(canvas.state.sourceText.now(), text0, "the bend changed the source of a locked diagram")
      Future.unit

  // ------------------------------------------------------------ a real diagram, frozen and clicked

  /** Bug: in the app, a drag on the selected arrow of this diagram added no bend. The diagram was frozen with
    * "Freeze layout", and a click selected the arrow. It has an HTML graph label, an `xlabel`, and rounded,
    * filled boxes.
    */
  private val styled =
    """digraph "G" {
      |  graph [
      |    label=<<TABLE BORDER="0" CELLBORDER="0" CELLSPACING="0" CELLPADDING="2"><TR><TD><FONT FACE="Menlo" POINT-SIZE="22" COLOR="#1F3A4D"><B>summon[Workflow[V2]]</B></FONT></TD></TR><TR><TD><FONT FACE="Helvetica" POINT-SIZE="12" COLOR="#1F3A4D">Follow each arrow to the type a given needs. Edited.</FONT></TD></TR></TABLE>>,
      |    labelloc="t"
      |  ];
      |  "a" [
      |    shape="box",
      |    fontname="Menlo",
      |    color="#428677",
      |    fillcolor="#EFF8F5",
      |    xlabel="outside",
      |    style="filled,rounded"
      |  ];
      |  "b" [
      |    shape="box",
      |    fontname="Menlo",
      |    color="#428677",
      |    fillcolor="#EFF8F5",
      |    style="filled,rounded"
      |  ];
      |  "a" -> "b";
      |}""".stripMargin

  test("a drag on the clicked arrow of a frozen diagram with styled nodes adds a bend"):
    withCanvas(styled, a): canvas =>
      sizeLikeApp(canvas)
      canvas.state.animateLayoutChanges.set(false)
      relayout(canvas, a)(canvas.state.freezeLayout())
        .flatMap: _ =>
          check(canvas.state.drawsFreeLayoutNow, "precondition: Freeze did not make a free layout")
          val ab       = arrowOf(canvas, a, b)
          val edge     = edgeGroup(canvas, ab.id).get
          val (x0, y0) = pointOnPath(edge, 0.5)
          click(dom.document.elementFromPoint(x0, y0), canvas, x0, y0)
          // A pause longer than the double-click time, so the next press is not a double-click.
          Waits
            .settle(canvas.box.querySelector("g.edge-endpoint-disk") != null, timeoutMs = 3000)
            .flatMap(_ => Waits.after(400))
            .map: _ =>
              check(canvas.state.selection.now().contains(ab.id), "precondition: the click did not select the arrow")
              val edge2    = edgeGroup(canvas, ab.id).get
              val (x1, y1) = pointOnPath(edge2, 0.5)
              val target   = dom.document.elementFromPoint(x1, y1)
              check(target.closest("g.edge") eq edge2, s"precondition: the point is not on the arrow: ${target.outerHTML.take(120)}")
              val before = canvas.svg
              dragFrom(canvas, target, x1, y1, -80, 10)()
              check(bendsOf(canvas, ab.id).nonEmpty, "the drag on the selected arrow added no bend")
              before
        .flatMap(before => redrawn(canvas, before, a))
        .map: _ =>
          // Bug, part 2: the drawn arrow started and ended thousands of points away from the rounded boxes.
          val ab    = arrowOf(canvas, a, b)
          val drawn = splineOf(edgeGroup(canvas, ab.id).get)
          val total = drawn.getTotalLength()
          val start = drawn.getPointAtLength(0)
          val end   = drawn.getPointAtLength(total)
          def box(id: NodeId) = canvas.nodeGroup(id).get.asInstanceOf[dom.svg.G].getBBox()
          def near(p: dom.svg.Point, r: dom.svg.Rect, what: String): Unit =
            val dx = math.max(0.0, math.max(r.x - p.x, p.x - (r.x + r.width)))
            val dy = math.max(0.0, math.max(r.y - p.y, p.y - (r.y + r.height)))
            check(math.hypot(dx, dy) <= 12.0, s"the $what of the bent arrow (${p.x}, ${p.y}) is not at its node box $r")
          near(start, box(a), "start")
          near(end, box(b), "end")
          val miss = distanceToPath(canvas, edgeGroup(canvas, ab.id).get, bendsOf(canvas, ab.id).head)
          check(miss <= 1.0, s"the drawn arrow does not go through the bend: $miss points away")

  /** Shapes that Graphviz draws as a path: a cylinder, and a rounded diamond. The size of `b` is not the default. */
  private val paths =
    """digraph G {
      |  a [shape=cylinder]
      |  b [shape=diamond, style=rounded, width=2, height=1]
      |  a -> b
      |}""".stripMargin

  test("a bent arrow between shapes drawn as a path ends on them, and the arrowhead points along the last part"):
    withCanvas(paths, a): canvas =>
      sizeLikeApp(canvas)
      canvas.state.animateLayoutChanges.set(false)
      relayout(canvas, a)(canvas.state.placeNodes(places - c)).flatMap: _ =>
        val ab = arrowOf(canvas, a, b)
        selectArrow(canvas, ab.id, 0).flatMap: _ =>
          val edge     = edgeGroup(canvas, ab.id).get
          val before   = canvas.svg
          val (x0, y0) = pointOnPath(edge, 0.5)
          val target   = dom.document.elementFromPoint(x0, y0)
          check(target.closest("g.edge") eq edge, s"precondition: the point is not on the arrow: ${target.outerHTML.take(120)}")
          dragFrom(canvas, target, x0, y0, 0, -70)()
          check(bendsOf(canvas, ab.id).nonEmpty, "the drag on the selected arrow added no bend")
          redrawn(canvas, before, a).map: _ =>
            val drawn = edgeGroup(canvas, ab.id).get
            val path  = splineOf(drawn)
            val total = path.getTotalLength()
            val start = path.getPointAtLength(0)
            val end   = path.getPointAtLength(total)
            val inner = path.getPointAtLength(total - 3)
            // The shape of a node: its largest path, as the user sees it.
            def shapeBox(id: NodeId) =
              canvas.nodeGroup(id).get.querySelectorAll("path").toSeq.collect { case e: dom.svg.Path => e.getBBox() }.maxBy(r => r.width * r.height)
            def distance(x: Double, y: Double, r: dom.svg.Rect): Double =
              val dx = math.max(0.0, math.max(r.x - x, x - (r.x + r.width)))
              val dy = math.max(0.0, math.max(r.y - y, y - (r.y + r.height)))
              math.hypot(dx, dy)
            val (boxA, boxB) = (shapeBox(a), shapeBox(b))
            check(distance(start.x, start.y, boxA) <= 1.0, s"the start (${start.x}, ${start.y}) is not on the cylinder $boxA")
            val head = drawn.querySelector("polygon")
            check(head != null, "the arrow has no arrowhead")
            val corners = head.getAttribute("points").trim.split("\\s+").toVector.map(_.split(',')).map(v => (v(0).toDouble, v(1).toDouble))
            check(corners.forall((x, y) => math.hypot(x - end.x, y - end.y) <= 12), s"the arrowhead is not at the end of the path: $corners, end (${end.x}, ${end.y})")
            val tip = corners.maxBy((x, y) => math.hypot(x - end.x, y - end.y))
            check(distance(tip._1, tip._2, boxB) <= 2.0, s"the arrowhead tip $tip is not at the diamond $boxB")
            // The arrowhead points along the last part of the path.
            val (ux, uy) = (end.x - inner.x, end.y - inner.y)
            val (vx, vy) = (tip._1 - end.x, tip._2 - end.y)
            val cos      = (ux * vx + uy * vy) / (math.hypot(ux, uy) * math.hypot(vx, vy))
            check(cos >= 0.95, s"the arrowhead does not point along the last part of the path: cos $cos")
            // The bent arrow goes through the bend.
            val miss = distanceToPath(canvas, drawn, bendsOf(canvas, ab.id).head)
            check(miss <= 1.0, s"the drawn arrow does not go through the bend: $miss points away")

  /** Nodes with no shape in the SVG: Graphviz draws only the text of a `plaintext` node. */
  private val plain =
    """digraph G {
      |  node [shape=plaintext]
      |  a -> b
      |}""".stripMargin

  test("a drag on the selected arrow between nodes with no shape adds a bend, and the arrow ends on the box of the node"):
    withCanvas(plain, a): canvas =>
      sizeLikeApp(canvas)
      canvas.state.animateLayoutChanges.set(false)
      relayout(canvas, a)(canvas.state.placeNodes(places - c)).flatMap: _ =>
        val ab = arrowOf(canvas, a, b)
        selectArrow(canvas, ab.id, 0).flatMap: _ =>
          val edge     = edgeGroup(canvas, ab.id).get
          val before   = canvas.svg
          val (x0, y0) = pointOnPath(edge, 0.5)
          val target   = dom.document.elementFromPoint(x0, y0)
          check(target.closest("g.edge") eq edge, s"precondition: the point is not on the arrow: ${target.outerHTML.take(120)}")
          dragFrom(canvas, target, x0, y0, 0, -70)()
          check(bendsOf(canvas, ab.id).nonEmpty, "the drag on the selected arrow added no bend")
          redrawn(canvas, before, a).map: _ =>
            // The path starts on the box of a: the box of its text, with the margin of Graphviz.
            val path  = splineOf(edgeGroup(canvas, ab.id).get)
            val start = path.getPointAtLength(0)
            val box   = canvas.nodeGroup(a).get.querySelector("text").asInstanceOf[dom.SVGLocatable].getBBox()
            val (cx, cy) = (box.x + box.width / 2, box.y + box.height / 2)
            check(math.hypot(start.x - cx, start.y - cy) > 5, s"the path starts at the center of a: (${start.x}, ${start.y})")
            check(math.abs(start.x - cx) <= 27 + 1 && math.abs(start.y - cy) <= 18 + 1, s"the path does not start on the box of a: (${start.x}, ${start.y}), center ($cx, $cy)")

  // ------------------------------------------------------------ add, move and remove a bend

  test("a drag on the path of a selected arrow adds a bend at the snapped place, and the drawing goes through it"):
    withFreeCanvas: canvas =>
      val ab = arrowOf(canvas, a, b)
      selectArrow(canvas, ab.id, 0).flatMap: _ =>
        val edge     = edgeGroup(canvas, ab.id).get
        val d0       = splineOf(edge).getAttribute("d")
        val before   = canvas.svg
        val (x0, y0) = pointOnPath(edge, 0.5)
        val target   = dom.document.elementFromPoint(x0, y0)
        check(target.closest("g.edge") eq edge, s"precondition: the point on the path is not on the arrow: ${target.outerHTML.take(120)}")
        val (dx, dy) = (17.0, -70.0)
        var during   = Option.empty[String]
        dragFrom(canvas, target, x0, y0, dx, dy):
          during =
            if !(canvas.svg eq before) then Some("the canvas drew a new svg during the drag")
            else if splineOf(edge).getAttribute("d") == d0 then Some("the path does not follow the pointer during the drag")
            else if bendsOf(canvas, ab.id).nonEmpty then Some("the layout changed during the drag")
            else None
        check(during.isEmpty, during.getOrElse(""))
        val raw      = layoutAt(canvas, x0 + dx, y0 + dy)
        val expected = LayoutPoint(math.round(raw.x / grid) * grid, math.round(raw.y / grid) * grid)
        same(bendsOf(canvas, ab.id), Vector(expected), "the new bend")
        check(onGrid(expected.x) && onGrid(expected.y), s"the bend is not on the grid: $expected")
        redrawn(canvas, before, a).flatMap: _ =>
          val drawn = edgeGroup(canvas, ab.id).get
          val miss  = distanceToPath(canvas, drawn, expected)
          check(miss <= 1.0, s"the drawn arrow does not go through the bend: $miss points away")
          // The arrowhead is at the end of the new path, and its tip is on the boundary of the target b.
          val head = drawn.querySelector("polygon")
          check(head != null, "the arrow has no arrowhead")
          val corners = head.getAttribute("points").trim.split("\\s+").toVector.map(_.split(',')).map(v => (v(0).toDouble, v(1).toDouble))
          val path    = splineOf(drawn)
          val tail    = path.getPointAtLength(path.getTotalLength())
          val lastIn  = path.getPointAtLength(path.getTotalLength() - 3)
          val tip     = corners.maxBy((x, y) => (x - tail.x) * (tail.x - lastIn.x) + (y - tail.y) * (tail.y - lastIn.y))
          check(corners.forall((x, y) => math.hypot(x - tail.x, y - tail.y) <= 12), s"the arrowhead is not at the end of the path: $corners, end (${tail.x}, ${tail.y})")
          // Graphviz puts the tip a little before the end point, for the stroke of the arrowhead: a tolerance of 2 points.
          val bBox = shapeOf(canvas, b).asInstanceOf[dom.SVGLocatable].getBBox()
          val (left, right, top, bottom) = (bBox.x, bBox.x + bBox.width, bBox.y, bBox.y + bBox.height)
          val inside   = tip._1 > left && tip._1 < right && tip._2 > top && tip._2 < bottom
          val dx       = (left - tip._1).max(tip._1 - right).max(0)
          val dy       = (top - tip._2).max(tip._2 - bottom).max(0)
          val distance = if inside then (tip._1 - left).min(right - tip._1).min(tip._2 - top).min(bottom - tip._2) else math.hypot(dx, dy)
          check(distance <= 2, s"the arrowhead tip $tip is $distance points from the boundary of b ($left, $top, $right, $bottom)")
          // The arrow is still selected, and its handle is at the bend.
          Waits.settle(handles(canvas).size == 1, timeoutMs = 2000).map: _ =>
            check(handles(canvas).size == 1, s"the arrow shows ${handles(canvas).size} handles after the new bend")
            val (hx, hy) = centerOf(handles(canvas).head)
            val (ex, ey) = clientOf(canvas, expected)
            check(math.abs(hx - ex) <= 1.5 && math.abs(hy - ey) <= 1.5, s"the handle is at ($hx, $hy), the bend at ($ex, $ey)")
            // One gesture is one step of the history.
            canvas.state.history.undo()
            same(bendsOf(canvas, ab.id), Vector.empty, "one undo did not remove the new bend")

  test("a drag of a bend handle moves the bend, with the snap, and Alt moves it with no snap"):
    withFreeCanvas: canvas =>
      val ab = arrowOf(canvas, a, b)
      relayout(canvas, a)(canvas.state.setArrowBends(ab.id, Vector(LayoutPoint(135, 90)))).flatMap: _ =>
        selectArrow(canvas, ab.id, 1).flatMap: _ =>
          val handle   = handles(canvas).head
          val (x0, y0) = centerOf(handle)
          val start    = layoutAt(canvas, x0, y0)
          near(start.x, 135, 0.6, "the handle x")
          near(start.y, 90, 0.6, "the handle y")
          val before = canvas.svg
          val scale  = pxPerPoint(canvas)
          dragFrom(canvas, handle, x0, y0, 23, 31)()
          val raw      = LayoutPoint(135 + 23 / scale, 90 - 31 / scale)
          val expected = LayoutPoint(math.round(raw.x / grid) * grid, math.round(raw.y / grid) * grid)
          same(bendsOf(canvas, ab.id), Vector(expected), "the moved bend")
          redrawn(canvas, before, a).flatMap: _ =>
            check(distanceToPath(canvas, edgeGroup(canvas, ab.id).get, expected) <= 1.0, "the drawn arrow does not go through the moved bend")
            canvas.state.history.undo()
            same(bendsOf(canvas, ab.id), Vector(LayoutPoint(135, 90)), "one undo did not put the bend back")
            canvas.state.history.redo()
            Waits.settle(handles(canvas).size == 1, timeoutMs = 3000).map: _ =>
              val h        = handles(canvas).head
              val (x1, y1) = centerOf(h)
              val from     = bendsOf(canvas, ab.id).head
              dragFrom(canvas, h, x1, y1, 11, 7, alt = true)()
              val moved = bendsOf(canvas, ab.id).head
              val s     = pxPerPoint(canvas)
              near(moved.x - from.x, 11 / s, 0.6, "the x move with Alt")
              near(moved.y - from.y, -7 / s, 0.6, "the y move with Alt")

  test("a double-click on a bend handle removes the bend, and the removal of the last bend gives the automatic route"):
    withFreeCanvas: canvas =>
      val ab     = arrowOf(canvas, a, b)
      val routed = splineOf(edgeGroup(canvas, ab.id).get).getAttribute("d")
      val two    = Vector(LayoutPoint(90, 90), LayoutPoint(180, 90))
      relayout(canvas, a)(canvas.state.setArrowBends(ab.id, two)).flatMap: _ =>
        check(splineOf(edgeGroup(canvas, ab.id).get).getAttribute("d") != routed, "precondition: the bends did not change the route")
        selectArrow(canvas, ab.id, 2).flatMap: _ =>
          def doubleClickHandle(i: Int): Unit =
            val first  = handles(canvas)(i)
            val (x, y) = centerOf(first)
            click(first, canvas, x, y)
            // The canvas builds the handles again after the first click.
            val second = handles(canvas)(i)
            click(second, canvas, x, y)
            // The browser sends the double-click to the element under the pointer: after the removal, that is not
            // the handle.
            Option(dom.document.elementFromPoint(x, y)).foreach(_.dispatchEvent(mouse("dblclick", x, y, buttons = 0)))
          val nodes0 = canvas.state.fullGraphNow().nodeIds
          val before = canvas.svg
          doubleClickHandle(0)
          same(bendsOf(canvas, ab.id), Vector(LayoutPoint(180, 90)), "a double-click did not remove the first bend")
          same(canvas.state.fullGraphNow().nodeIds, nodes0, "a double-click on a handle added a node")
          redrawn(canvas, before, a).flatMap: _ =>
            selectArrow(canvas, ab.id, 1).flatMap: _ =>
              val before2 = canvas.svg
              doubleClickHandle(0)
              check(canvas.state.layout.now().arrows.isEmpty, s"the last bend stays: ${canvas.state.layout.now().arrows}")
              redrawn(canvas, before2, a).map: _ =>
                same(splineOf(edgeGroup(canvas, ab.id).get).getAttribute("d"), routed, "the arrow with no bend does not have the automatic route")
                canvas.state.history.undo()
                same(bendsOf(canvas, ab.id), Vector(LayoutPoint(180, 90)), "one undo did not put the last bend back")

  test("Reset arrow route removes all the bends of the selected arrows in one step"):
    withFreeCanvas: canvas =>
      val ab = arrowOf(canvas, a, b)
      val bc = arrowOf(canvas, b, c)
      val ac = arrowOf(canvas, a, c)
      relayout(canvas, a):
        canvas.state.setArrowBends(ab.id, Vector(LayoutPoint(135, 90)))
        canvas.state.setArrowBends(bc.id, Vector(LayoutPoint(360, -90)))
        canvas.state.setArrowBends(ac.id, Vector(LayoutPoint(90, -180)))
      .map: _ =>
        val commands = Commands(canvas.state, RouterCommands(Router()))
        canvas.state.selection.set(ElementIds.from(ab.id, bc.id))
        check(commands.all.resetArrowRoute.isVisible(canvas.state.selection.now()), "Reset arrow route is not visible for two arrows with bends")
        val withBends = canvas.state.layout.now()
        canvas.state.history.markGesture()
        commands.all.resetArrowRoute.execute()
        same(bendsOf(canvas, ab.id), Vector.empty, "the first arrow keeps its bends")
        same(bendsOf(canvas, bc.id), Vector.empty, "the second arrow keeps its bends")
        same(bendsOf(canvas, ac.id), Vector(LayoutPoint(90, -180)), "an arrow that is not selected lost its bends")
        canvas.state.history.undo()
        same(canvas.state.layout.now(), withBends, "one undo did not put back the bends")
        // The palette has the command.
        check(commands.byHeader.values.exists(_.contains(commands.all.resetArrowRoute)), "⌘K does not list Reset arrow route")

  // ------------------------------------------------------------ the other gestures on a selected arrow

  test("with bends, a drag of the arrow endpoint still moves the arrow to a different node"):
    withFreeCanvas: canvas =>
      val ab = arrowOf(canvas, a, b)
      relayout(canvas, a)(canvas.state.setArrowBends(ab.id, Vector(LayoutPoint(135, 90)))).flatMap: _ =>
        selectArrow(canvas, ab.id, 1).map: _ =>
          val disk     = canvas.box.querySelector("g.edge-endpoint-disk-target")
          check(disk != null, "the selected arrow has no target disk")
          val (x0, y0) = centerOf(disk)
          val shapeC   = shapeOf(canvas, c)
          val (x1, y1) = centerOf(shapeC)
          press(disk, x0, y0)
          moveTo(canvas, (x0 + x1) / 2, (y0 + y1) / 2)
          val action = canvas.state.mouseAction.now()
          check(action match { case _: MouseAction.MoveArrowEndpointAction => true; case _ => false }, s"the action is $action")
          dom.document.elementFromPoint(x1, y1).dispatchEvent(mouse("mousemove", x1, y1))
          dom.document.elementFromPoint(x1, y1).dispatchEvent(mouse("mouseup", x1, y1, buttons = 0))
          val arrows = canvas.state.fullGraphNow().arrows.values.map(x => (x.source, x.target)).toVector
          check(arrows.count(_ == (a, c)) == 2 && !arrows.contains((a, b)), s"the endpoint drag did not move the arrow to c: $arrows")

  test("a press on an arrow that is not selected selects it, and a drag on it adds no bend"):
    withFreeCanvas: canvas =>
      val ab = arrowOf(canvas, a, b)
      canvas.state.selection.set(ElementIds())
      Waits.after(30).map: _ =>
        val edge     = edgeGroup(canvas, ab.id).get
        val (x0, y0) = pointOnPath(edge, 0.5)
        val target   = dom.document.elementFromPoint(x0, y0)
        press(target, x0, y0)
        same(canvas.state.selection.now(), ElementIds.from(ab.id), "a press on an arrow did not select it")
        moveTo(canvas, x0, y0 - 40)
        release(canvas, x0, y0 - 40)
        same(bendsOf(canvas, ab.id), Vector.empty, "a drag on an arrow that was not selected added a bend")

  // ------------------------------------------------------------ node moves

  test("a drag of both ends of an arrow moves its bends with them; a drag of one end keeps the bends"):
    withFreeCanvas: canvas =>
      val ab = arrowOf(canvas, a, b)
      val bc = arrowOf(canvas, b, c)
      relayout(canvas, a):
        canvas.state.setArrowBends(ab.id, Vector(LayoutPoint(135, 90)))
        canvas.state.setArrowBends(bc.id, Vector(LayoutPoint(360, -90)))
      .flatMap: _ =>
        // a and b move: the bend of a -> b moves with them, the bend of b -> c stays.
        canvas.state.selection.set(ElementIds.from(a, b))
        val shape    = shapeOf(canvas, a)
        val (x0, y0) = centerOf(shape)
        val before   = canvas.svg
        val a0       = canvas.state.layout.now().point("a").get
        dragFrom(canvas, shape, x0, y0, 36, 18, alt = true)()
        val a1 = canvas.state.layout.now().point("a").get
        val (mx, my) = (a1.x - a0.x, a1.y - a0.y)
        check(mx != 0, "precondition: the drag did not move a")
        val moved = bendsOf(canvas, ab.id).head
        near(moved.x, 135 + mx, 0.02, "the x of the bend of a -> b")
        near(moved.y, 90 + my, 0.02, "the y of the bend of a -> b")
        same(bendsOf(canvas, bc.id), Vector(LayoutPoint(360, -90)), "the bend of an arrow with one moved end moved")
        canvas.state.history.undo()
        same(bendsOf(canvas, ab.id), Vector(LayoutPoint(135, 90)), "one undo did not put back the bend with the nodes")
        redrawn(canvas, before, a)

  // ------------------------------------------------------------ Freeze and Arrange

  test("Arrange removes the bends with the positions, and Freeze writes no bend"):
    withFreeCanvas: canvas =>
      val ab = arrowOf(canvas, a, b)
      relayout(canvas, a)(canvas.state.setArrowBends(ab.id, Vector(LayoutPoint(135, 90)))).flatMap: _ =>
        relayout(canvas, a)(canvas.state.arrangeLayout()).flatMap: _ =>
          same(canvas.state.layout.now().arrows, Map.empty, "Arrange kept the bends")
          relayout(canvas, a)(canvas.state.freezeLayout()).map: _ =>
            check(canvas.state.layout.now().hasPositions, "precondition: Freeze stored no position")
            same(canvas.state.layout.now().arrows, Map.empty, "Freeze wrote bends")

  // ------------------------------------------------------------ Mermaid

  test("a Mermaid flowchart in a free layout gets a bend from a drag on its selected arrow"):
    withCanvasWhen(mermaid, "the Mermaid drawing shows a node")(_.svg.querySelector("g.node") != null): canvas =>
      sizeLikeApp(canvas)
      canvas.state.animateLayoutChanges.set(false)
      val (pa, pb, pc) = (NodeId("A"), NodeId("B"), NodeId("C"))
      relayout(canvas, pb)(canvas.state.placeNodes(Map(pa -> LayoutPoint(0, 0), pb -> LayoutPoint(270, 0), pc -> LayoutPoint(540, 0)))).flatMap: _ =>
        val ab = arrowOf(canvas, pa, pb)
        selectArrow(canvas, ab.id, 0).flatMap: _ =>
          val edge = edgeGroup(canvas, ab.id)
          check(edge.isDefined, "the free layout of the flowchart has no Graphviz arrow A -> B")
          val (x0, y0) = pointOnPath(edge.get, 0.5)
          val before   = canvas.svg
          dragFrom(canvas, dom.document.elementFromPoint(x0, y0), x0, y0, 0, 60)()
          val bends = bendsOf(canvas, ab.id)
          check(bends.size == 1, s"the drag did not add one bend: $bends")
          redrawn(canvas, before, pb).map: _ =>
            check(distanceToPath(canvas, edgeGroup(canvas, ab.id).get, bends.head) <= 1.0, "the drawn arrow does not go through the bend")

end BendPointsSpec
