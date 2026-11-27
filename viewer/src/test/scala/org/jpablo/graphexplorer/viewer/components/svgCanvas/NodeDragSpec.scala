package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.airstream.ownership.ManualOwner
import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.{DiagramLayout, LayoutPoint}
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.viewer.components.selection.MermaidSelectionStrategy
import org.jpablo.graphexplorer.viewer.domUtils.elementsFromPoint
import org.jpablo.graphexplorer.viewer.models.{ElementIds, NodeId}
import org.jpablo.graphexplorer.viewer.state.ManualLayoutSpec
import org.jpablo.graphexplorer.viewer.state.mouseActions.MouseAction
import org.jpablo.graphexplorer.viewer.utils.{TestHelpers, Waits}
import org.scalajs.dom

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/** Manual layout, part 2: a drag of nodes on the canvas (ADR 0004).
  *
  * The tests send real mouse events to the rendered canvas. In a free layout, a press on a node and a drag moves the
  * node, and the release stores one move. The move is in layout points: the test measures the points of a node shape
  * against its client box, so that the zoom is in the expected value. The y axis of the layout goes up, and the y
  * axis of the screen goes down.
  */
class NodeDragSpec extends FunSuite with TestHelpers with LoggedChecks with CanvasFixture:

  def checkPrefix = "NodeDragSpec"

  // Through the Scala.js test bridge and the sbt client, a munit failure arrives with no message. An AssertionError
  // keeps its message.
  override def check(condition: Boolean, clue: => String): Unit =
    if !condition then throw AssertionError(s"[$checkPrefix] $clue")

  def canvasDiagramId = "node-drag-spec"

  // `ElementIds` is a case class: its equality compares the ids.
  private given CanEqual[ElementIds, ElementIds] = CanEqual.derived

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

  /** The spacing of the snap grid, in layout points. */
  private val grid = 9.0

  /** The tolerance of a layout value, in points. */
  private val tolerance = 0.6

  private val a = NodeId("a")
  private val b = NodeId("b")
  private val c = NodeId("c")
  private val d = NodeId("d")

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
  private def press(target: dom.Element, x: Double, y: Double, shift: Boolean = false): Unit =
    val init = new dom.PointerEventInit {}
    init.clientX = x
    init.clientY = y
    init.button = 0
    init.buttons = 1
    init.bubbles = true
    init.shiftKey = shift
    target.dispatchEvent(new dom.PointerEvent("pointerdown", init))
    target.dispatchEvent(mouse("mousedown", x, y, shift = shift))

  private def moveTo(canvas: TestCanvas, x: Double, y: Double, alt: Boolean = false): Unit =
    canvas.svg.dispatchEvent(mouse("mousemove", x, y, alt = alt))

  private def release(canvas: TestCanvas, x: Double, y: Double, alt: Boolean = false): Unit =
    canvas.svg.dispatchEvent(mouse("mouseup", x, y, alt = alt, buttons = 0))

  private def centerOf(e: dom.Element): (Double, Double) =
    val r = e.getBoundingClientRect()
    (r.left + r.width / 2, r.top + r.height / 2)

  /** The shape of a node group: the element that a press hits. */
  private def shapeOf(g: dom.Element): dom.Element =
    Option(g.querySelector("ellipse")).orElse(Option(g.querySelector("polygon"))).orElse(Option(g.querySelector("rect"))).get

  /** Client pixels for one layout point, from the shape of a Graphviz node: its width in points and its client width. */
  private def pxPerPoint(g: dom.Element): Double =
    val shape = shapeOf(g)
    val points =
      if shape.tagName.equalsIgnoreCase("ellipse") then 2 * shape.getAttribute("rx").toDouble
      else
        val xs = shape.getAttribute("points").trim.split("\\s+").toVector.filter(_.contains(',')).map(_.split(',')(0).toDouble)
        xs.max - xs.min
    shape.getBoundingClientRect().width / points

  /** Drag the node `id`: press at the center of its shape, move in `steps` steps by `(dx, dy)` client pixels in all,
    * and release. `beforeRelease` runs after the last move.
    */
  private def drag(canvas: TestCanvas, id: NodeId, dx: Double, dy: Double, alt: Boolean = false, steps: Int = 4)(
      beforeRelease: => Unit = ()
  ): Unit =
    val g = canvas.nodeGroup(id)
    check(g.isDefined, s"precondition: the canvas does not show the node $id")
    val shape    = shapeOf(g.get)
    val (x0, y0) = centerOf(shape)
    press(shape, x0, y0)
    for i <- 1 to steps do moveTo(canvas, x0 + dx * i / steps, y0 + dy * i / steps, alt)
    beforeRelease
    release(canvas, x0 + dx, y0 + dy, alt)

  private def layoutOf(canvas: TestCanvas): DiagramLayout = canvas.state.layout.now()

  private def point(canvas: TestCanvas, id: NodeId): LayoutPoint =
    val p = layoutOf(canvas).point(id.value)
    check(p.isDefined, s"the layout has no position for $id: ${layoutOf(canvas)}")
    p.get

  private def near(obtained: Double, expected: Double, what: String): Unit =
    check(math.abs(obtained - expected) <= tolerance, s"$what: obtained $obtained, expected $expected")

  /** Freeze the layout of the canvas, and wait for the free layout drawing. */
  private def freeze(canvas: TestCanvas, first: NodeId): Future[Unit] =
    val before = canvas.svg
    canvas.state.history.markGesture()
    canvas.state.freezeLayout()
    check(layoutOf(canvas).hasPositions, "precondition: Freeze stored no position")
    redrawn(canvas, before, first)

  /** Wait for a new svg that shows the node `first`, and for the frame after it. */
  private def redrawn(canvas: TestCanvas, before: dom.svg.SVG, first: NodeId): Future[Unit] =
    Waits
      .settle(!(canvas.svg eq before) && canvas.nodeGroup(first).isDefined, timeoutMs = 5000)
      .flatMap(_ => Waits.after(30))
      .map(_ => check(!(canvas.svg eq before), "the canvas did not draw the diagram again"))

  /** Give the svg the size of the canvas, as the app stylesheet does. The test page has no app stylesheet: the svg
    * then gets the height of its viewBox at the width of the box, and it overflows the box. (See `FitTestCanvas`.)
    */
  private def sizeLikeApp(canvas: TestCanvas): Unit =
    val rules = dom.document.createElement("style")
    rules.textContent =
      ".floating-toolbar { position: absolute; } " +
        "#canvas-container { width: 100%; height: 100%; } " +
        "#canvas-container > svg { display: block; width: 100%; height: 100%; }"
    canvas.box.appendChild(rules)

  /** A canvas of a free layout, with no layout animation, so that each measure is at the final place. */
  private def withFreeCanvas(source: String, first: NodeId)(body: TestCanvas => Future[Unit]): Future[Unit] =
    withCanvas(source, first): canvas =>
      sizeLikeApp(canvas)
      canvas.state.animateLayoutChanges.set(false)
      freeze(canvas, first).flatMap(_ => body(canvas))

  // ------------------------------------------------------------ the drag in a free layout

  test("a drag of a node in a free layout moves it by the client delta in layout points, with the y axis up"):
    withFreeCanvas(dot, a): canvas =>
      // A zoom that is not 1 must be in the conversion.
      canvas.state.zoomIn()
      canvas.state.zoomIn()
      Waits.after(50).flatMap: _ =>
        val start = point(canvas, a)
        val other = point(canvas, c)
        val scale = pxPerPoint(canvas.nodeGroup(a).get)
        val (dx, dy) = (60.0, 40.0)
        canvas.state.history.markGesture()
        drag(canvas, a, dx, dy, alt = true)()
        val moved = point(canvas, a)
        near(moved.x - start.x, dx / scale, "the x move of a")
        near(moved.y - start.y, -dy / scale, "the y move of a (the layout y goes up)")
        same(point(canvas, c), other, "a node that the drag did not move moved")
        Future.unit

  test("during a drag the node follows the pointer with no new render, and the release draws it at its new place"):
    withFreeCanvas(dot, a): canvas =>
      val before   = canvas.svg
      val layout0  = layoutOf(canvas)
      val center0  = ManualLayoutSpec.centerOf(canvas.nodeGroup(a).get)
      val other0   = ManualLayoutSpec.centerOf(canvas.nodeGroup(d).get)
      val oldA     = canvas.nodeGroup(a).get
      var duringOk = Option.empty[String]
      var dropAt   = (0.0, 0.0)
      drag(canvas, a, 50, 30, alt = true):
        val g = canvas.nodeGroup(a).get
        dropAt = centerOf(shapeOf(g))
        duringOk =
          if !(canvas.svg eq before) then Some("the canvas drew a new svg during the drag")
          else if layoutOf(canvas) != layout0 then Some("the layout changed during the drag")
          else if !Option(g.getAttribute("transform")).exists(_.contains("translate")) then
            Some(s"the node does not follow the pointer: transform=${g.getAttribute("transform")}")
          else None
      check(duringOk.isEmpty, duringOk.getOrElse(""))
      // The old node stays at the drop place until the new svg replaces it, so that the screen never shows it at its
      // old place. The release must not put the node back first: the layout changes after the event handler.
      val dropped = Option(oldA.getAttribute("transform"))
      check(dropped.exists(_.contains("translate")), s"the release put the node back before the new render: $dropped")
      redrawn(canvas, before, a).map: _ =>
        val moved = point(canvas, a)
        val delta = (moved.x - layout0.point("a").get.x, moved.y - layout0.point("a").get.y)
        // Right and down on the screen: the layout y goes up, so the y move is negative.
        check(delta._1 > 0 && delta._2 < 0, s"the move is not right and down: $delta")
        // The new drawing has the node at its new place, relative to a node that did not move.
        val center1 = ManualLayoutSpec.centerOf(canvas.nodeGroup(a).get)
        val other1  = ManualLayoutSpec.centerOf(canvas.nodeGroup(d).get)
        near((center1.x - other1.x) - (center0.x - other0.x), delta._1, "the drawn x move")
        near((center1.y - other1.y) - (center0.y - other0.y), delta._2, "the drawn y move")
        // The drag sets Auto to off, so the view does not fit again: the node stays where the person dropped it.
        check(!canvas.state.autoFit.now(), "Auto is still on after a drag")
        val landed = centerOf(shapeOf(canvas.nodeGroup(a).get))
        check(
          math.abs(landed._1 - dropAt._1) <= 1.5 && math.abs(landed._2 - dropAt._2) <= 1.5,
          s"the node did not land where it was dropped: dropped at $dropAt, drawn at $landed"
        )

  test("a drag snaps the center of the node to the grid, and Alt drags with no snap"):
    withFreeCanvas(dot, a): canvas =>
      val scale  = pxPerPoint(canvas.nodeGroup(a).get)
      val start  = point(canvas, a)
      val (dx, dy) = (23.0, 17.0)
      // During the drag, the canvas draws the grid, and the center of the node is on a dot of the grid.
      var onDrawnGrid = Option.empty[String]
      drag(canvas, a, dx, dy):
        val g       = canvas.nodeGroup(a).get
        val pattern = Option(canvas.svg.querySelector(".gx-snap-grid pattern"))
        onDrawnGrid = pattern match
          case None => Some("the canvas does not draw the grid during a drag")
          case Some(p) =>
            val center  = ManualLayoutSpec.centerOf(g)
            val shift   = """translate\(([-0-9.e]+) ([-0-9.e]+)\)""".r.findFirstMatchIn(Option(g.getAttribute("transform")).getOrElse(""))
            val (tx, ty) = shift.fold((0.0, 0.0))(m => (m.group(1).toDouble, m.group(2).toDouble))
            // The svg y axis goes down: `centerOf` gives -cy.
            val (sx, sy) = (center.x + tx, -center.y + ty)
            val kx = (sx - p.getAttribute("x").toDouble - grid / 2) / grid
            val ky = (sy - p.getAttribute("y").toDouble - grid / 2) / grid
            if math.abs(kx - math.round(kx)) < 0.01 && math.abs(ky - math.round(ky)) < 0.01 then None
            else Some(s"the node is not on a dot of the drawn grid: steps ($kx, $ky)")
      check(onDrawnGrid.isEmpty, onDrawnGrid.getOrElse(""))
      check(canvas.svg.querySelector(".gx-snap-grid") == null, "the grid stays after the drag")
      val snapped = point(canvas, a)
      val rawX    = start.x + dx / scale
      val rawY    = start.y - dy / scale
      near(snapped.x, math.round(rawX / grid) * grid, "the snapped x")
      near(snapped.y, math.round(rawY / grid) * grid, "the snapped y")
      check(math.abs(snapped.x / grid - math.round(snapped.x / grid)) < 1e-6, s"x is not on the grid: $snapped")
      check(math.abs(snapped.y / grid - math.round(snapped.y / grid)) < 1e-6, s"y is not on the grid: $snapped")
      // With Alt, the node goes exactly with the pointer.
      val startB = point(canvas, b)
      val scaleB = pxPerPoint(canvas.nodeGroup(b).get)
      drag(canvas, b, dx, dy, alt = true)()
      val freeB = point(canvas, b)
      near(freeB.x - startB.x, dx / scaleB, "the x move with Alt")
      near(freeB.y - startB.y, -dy / scaleB, "the y move with Alt")
      Future.unit

  test("a press and a release with a small move is a click: it selects the node and moves nothing"):
    withFreeCanvas(dot, a): canvas =>
      canvas.state.selection.set(ElementIds.from(b, c))
      val layout0 = layoutOf(canvas)
      drag(canvas, a, 2, 1)()
      same(layoutOf(canvas), layout0, "a small move changed the layout")
      same(canvas.state.selection.now(), ElementIds.from(a), "a click did not select the node")
      // A click on a node of a multi-selection selects that node alone.
      canvas.state.selection.set(ElementIds.from(a, b))
      drag(canvas, b, 1, 0)()
      same(canvas.state.selection.now(), ElementIds.from(b), "a click on a selected node did not select it alone")
      same(layoutOf(canvas), layout0, "a click changed the layout")
      Future.unit

  test("a drag of a node of a multi-selection moves all the selected nodes by the same delta"):
    withFreeCanvas(dot, a): canvas =>
      canvas.state.selection.set(ElementIds.from(a, c))
      val (a0, b0, c0, d0) = (point(canvas, a), point(canvas, b), point(canvas, c), point(canvas, d))
      drag(canvas, a, 40, 30, alt = true)()
      val (a1, c1) = (point(canvas, a), point(canvas, c))
      check(a1 != a0, "the pressed node did not move")
      near(c1.x - c0.x, a1.x - a0.x, "the x move of the other selected node")
      near(c1.y - c0.y, a1.y - a0.y, "the y move of the other selected node")
      same(point(canvas, b), b0, "a node that is not selected moved")
      same(point(canvas, d), d0, "a node that is not selected moved")
      same(canvas.state.selection.now(), ElementIds.from(a, c), "the drag changed the selection")
      Future.unit

  test("a drag of a member of a selected group moves all the nodes of the group"):
    withFreeCanvas(dot, a): canvas =>
      val group = canvas.state.fullGraphNow().membership(a)
      check(group.isDefined, "precondition: a is not in a group")
      canvas.state.selection.set(ElementIds.from(group.get))
      val (a0, b0, c0) = (point(canvas, a), point(canvas, b), point(canvas, c))
      drag(canvas, a, -30, 20, alt = true)()
      val (a1, b1) = (point(canvas, a), point(canvas, b))
      check(a1 != a0, "the pressed node did not move")
      near(b1.x - b0.x, a1.x - a0.x, "the x move of the other member")
      near(b1.y - b0.y, a1.y - a0.y, "the y move of the other member")
      same(point(canvas, c), c0, "a node outside the group moved")
      Future.unit

  test("one drag is one undo step, and redo puts the drag back"):
    withFreeCanvas(dot, a): canvas =>
      val frozen = layoutOf(canvas)
      drag(canvas, a, 30, 30, alt = true, steps = 8)()
      val moved = layoutOf(canvas)
      check(moved != frozen, "precondition: the drag did not move the node")
      check(canvas.state.history.canUndoNow, "the drag is not a step of the history")
      canvas.state.history.undo()
      same(layoutOf(canvas), frozen, "one undo did not revert the whole drag")
      canvas.state.history.redo()
      same(layoutOf(canvas), moved, "redo did not put the drag back")
      Future.unit

  // A lock protects the source. The layout is not the source: the person arranges a locked, generated file by hand
  // (ADR 0004). So a locked diagram takes a drag.
  test("a locked diagram takes a drag, because the layout is not the source"):
    withFreeCanvas(dot, a): canvas =>
      val owner  = ManualOwner()
      val errors = mutable.Buffer.empty[String]
      canvas.state.errorBus.events.foreach(errors += _)(using owner)
      canvas.state.metadata.locked.set(true)
      val layout0 = layoutOf(canvas)
      val text0   = canvas.state.sourceText.now()
      drag(canvas, a, 40, 40, alt = true)()
      owner.killSubscriptions()
      check(layoutOf(canvas) != layout0, "a locked diagram refused the drag")
      same(canvas.state.sourceText.now(), text0, "the drag changed the source of a locked diagram")
      check(!errors.exists(_.contains("locked")), s"the drag of a locked diagram gave a lock error: $errors")
      Future.unit

  // ------------------------------------------------------------ the other gestures

  test("in a free layout, a drag on the empty canvas still draws a selection rectangle"):
    withFreeCanvas(dot, a): canvas =>
      canvas.state.selection.set(ElementIds())
      val layout0 = layoutOf(canvas)
      // A start point beside the node a, on the svg and on no node and no arrow (a group box is fine: it is not a
      // node). The end point is at the opposite corner of a, so that the rectangle covers a.
      val ra = canvas.nodeGroup(a).get.getBoundingClientRect()
      def free(x: Double, y: Double): Boolean =
        val under = dom.document.elementsFromPoint(x, y)
        under.exists(_ eq canvas.svg) && under.forall(e => e.closest("g.node, g.edge") == null)
      val corners = for
        k      <- (6 to 60 by 3).toVector
        (x, y) <- Vector((ra.left - k, ra.top - k), (ra.right + k, ra.top - k), (ra.left - k, ra.bottom + k), (ra.right + k, ra.bottom + k))
      yield (x.toDouble, y.toDouble)
      val start = corners.find(free(_, _))
      check(start.isDefined, s"precondition: no free point beside the node a: $ra")
      val (x0, y0) = start.get
      val (x1, y1) = (if x0 < ra.left then ra.right + 2 else ra.left - 2, if y0 < ra.top then ra.bottom + 2 else ra.top - 2)
      val target   = dom.document.elementFromPoint(x0, y0)
      press(target, x0, y0)
      moveTo(canvas, x1, y1)
      val action = canvas.state.mouseAction.now()
      check(action match { case _: MouseAction.ExtendSelectionAction => true; case _ => false }, s"the action is $action")
      release(canvas, x1, y1)
      check(canvas.state.selection.now().contains(a), s"the rectangle did not select a: ${canvas.state.selection.now()}")
      same(layoutOf(canvas), layout0, "the selection rectangle changed the layout")
      Future.unit

  test("in a free layout, a drag of an arrow endpoint is still an endpoint drag"):
    withFreeCanvas(dot, a): canvas =>
      val arrow = canvas.state.fullGraphNow().arrows.values.find(x => x.source == a && x.target == b)
      check(arrow.isDefined, "precondition: the diagram has no arrow a -> b")
      canvas.state.selection.set2(arrow.get.id)
      def disk = Option(canvas.box.querySelector("g.edge-endpoint-disk-target"))
      Waits.settle(disk.isDefined, timeoutMs = 3000).map: _ =>
        check(disk.isDefined, "precondition: the selected arrow has no endpoint disk")
        val layout0  = layoutOf(canvas)
        val (x0, y0) = centerOf(disk.get)
        press(disk.get, x0, y0)
        moveTo(canvas, x0 + 30, y0 + 30)
        val action = canvas.state.mouseAction.now()
        check(action match { case _: MouseAction.MoveArrowEndpointAction => true; case _ => false }, s"the action is $action")
        canvas.state.mouseAction.inactive()
        same(layoutOf(canvas), layout0, "an endpoint drag changed the layout")

  // ------------------------------------------------------------ an automatic layout

  private def freezeOffer(canvas: TestCanvas): Option[dom.Element] =
    Option(canvas.box.querySelector("#gx-freeze-offer"))

  test("a drag on a node of an automatic diagram moves nothing and offers Freeze; the offer freezes in one step"):
    withCanvas(dot, a): canvas =>
      val before = canvas.svg
      check(freezeOffer(canvas).isEmpty, "precondition: the canvas offers Freeze before a drag")
      drag(canvas, a, 40, 30)()
      check(!layoutOf(canvas).hasPositions, s"a drag of an automatic diagram stored positions: ${layoutOf(canvas)}")
      check(canvas.svg eq before, "a drag of an automatic diagram drew the diagram again")
      Waits.settle(freezeOffer(canvas).isDefined, timeoutMs = 1000).flatMap: _ =>
        check(freezeOffer(canvas).isDefined, "the drag did not offer Freeze")
        val accept = Option(freezeOffer(canvas).get.querySelector("button.gx-freeze-accept"))
        check(accept.isDefined, s"the offer has no Freeze button: ${freezeOffer(canvas).get.outerHTML}")
        val (x, y) = centerOf(accept.get)
        press(accept.get, x, y)
        accept.get.asInstanceOf[dom.html.Element].click()
        check(layoutOf(canvas).nodes.keySet == Set("a", "b", "c", "d"), s"the offer did not freeze: ${layoutOf(canvas)}")
        Waits.settle(freezeOffer(canvas).isEmpty, timeoutMs = 1000).map: _ =>
          check(freezeOffer(canvas).isEmpty, "the offer stays after Freeze")
          canvas.state.history.undo()
          check(!layoutOf(canvas).hasPositions, s"one undo did not revert Freeze: ${layoutOf(canvas)}")

  // ------------------------------------------------------------ Mermaid

  private def mermaidNode(canvas: TestCanvas, id: NodeId): Option[dom.Element] =
    canvas.svg.querySelectorAll(MermaidSelectionStrategy.nodeSelector).toSeq.find(e => MermaidSelectionStrategy.extractNodeId(e) == id)

  // Before the fix in `ViewerState.finalSVG`, the canvas kept the Mermaid drawing of a flowchart with positions: the
  // new strategy came with the old drawing for a moment, and the canvas did not show the Graphviz drawing after it.
  test("a drag of a node of a Mermaid flowchart in a free layout moves the node"):
    withCanvasWhen(mermaid, "the Mermaid drawing shows the node B")(cv => mermaidNode(cv, NodeId("B")).isDefined): canvas =>
      canvas.state.animateLayoutChanges.set(false)
      val before = canvas.svg
      canvas.state.history.markGesture()
      canvas.state.placeNodes(Map(NodeId("A") -> LayoutPoint(0, 0), NodeId("B") -> LayoutPoint(200, 0), NodeId("C") -> LayoutPoint(400, 0)))
      redrawn(canvas, before, NodeId("B")).map: _ =>
        val start = point(canvas, NodeId("B"))
        val scale = pxPerPoint(canvas.nodeGroup(NodeId("B")).get)
        drag(canvas, NodeId("B"), 40, -30, alt = true)()
        val moved = point(canvas, NodeId("B"))
        near(moved.x - start.x, 40 / scale, "the x move of B")
        near(moved.y - start.y, 30 / scale, "the y move of B")
        same(point(canvas, NodeId("A")), LayoutPoint(0, 0), "A moved")

  test("a drag on a node of an automatic Mermaid flowchart offers Freeze"):
    withCanvasWhen(mermaid, "the Mermaid drawing shows the node A")(cv => mermaidNode(cv, NodeId("A")).isDefined): canvas =>
      val node     = mermaidNode(canvas, NodeId("A")).get
      val (x0, y0) = centerOf(node)
      val target   = dom.document.elementFromPoint(x0, y0)
      press(target, x0, y0)
      moveTo(canvas, x0 + 30, y0 + 30)
      release(canvas, x0 + 30, y0 + 30)
      check(!layoutOf(canvas).hasPositions, s"a drag of an automatic flowchart stored positions: ${layoutOf(canvas)}")
      Waits.settle(freezeOffer(canvas).isDefined, timeoutMs = 1000).map: _ =>
        check(freezeOffer(canvas).isDefined, "the drag did not offer Freeze")

  // ------------------------------------------------------------ the keyboard

  test("Shift and an arrow key move the selected nodes of a free layout by one grid step"):
    withFreeCanvas(dot, a): canvas =>
      canvas.state.selection.set(ElementIds.from(a))
      val start     = point(canvas, a)
      val container = canvas.box.querySelector("#canvas-container")
      def key(name: String): Unit =
        val init = new dom.KeyboardEventInit {}
        init.key = name
        init.shiftKey = true
        init.bubbles = true
        init.cancelable = true
        container.dispatchEvent(new dom.KeyboardEvent("keydown", init))
      key("ArrowRight")
      near(point(canvas, a).x - start.x, grid, "Shift+Right")
      key("ArrowUp")
      near(point(canvas, a).y - start.y, grid, "Shift+Up (the layout y goes up)")
      same(canvas.state.selection.now(), ElementIds.from(a), "a nudge changed the selection")
      canvas.state.history.undo()
      near(point(canvas, a).y - start.y, 0, "undo of one nudge")
      Future.unit

  // Before the fix, the view kept the nudged node still on the screen: the anchor of the new layout was the selected
  // node, and the rest of the drawing moved by one step in the other direction.
  test("a nudge moves the node on the screen, and the other nodes stay where they are"):
    withFreeCanvas(dot, a): canvas =>
      canvas.state.selection.set(ElementIds.from(a))
      val container = canvas.box.querySelector("#canvas-container")
      def key(name: String): Unit =
        val init = new dom.KeyboardEventInit {}
        init.key = name
        init.shiftKey = true
        init.bubbles = true
        init.cancelable = true
        container.dispatchEvent(new dom.KeyboardEvent("keydown", init))
      Waits.after(30).flatMap: _ =>
        val scale  = pxPerPoint(canvas.nodeGroup(a).get)
        val a0     = centerOf(shapeOf(canvas.nodeGroup(a).get))
        val d0     = centerOf(shapeOf(canvas.nodeGroup(d).get))
        val before = canvas.svg
        key("ArrowRight")
        redrawn(canvas, before, a).flatMap: _ =>
          val a1 = centerOf(shapeOf(canvas.nodeGroup(a).get))
          val d1 = centerOf(shapeOf(canvas.nodeGroup(d).get))
          check(math.abs(d1._1 - d0._1) <= 1 && math.abs(d1._2 - d0._2) <= 1, s"a node that the nudge did not move moved on the screen: $d0 -> $d1")
          check(math.abs(a1._1 - a0._1 - grid * scale) <= 1.5, s"the nudged node did not move right by one grid step on the screen: ${a0._1} -> ${a1._1}, step ${grid * scale}")
          check(math.abs(a1._2 - a0._2) <= 1, s"the nudged node moved up or down: $a0 -> $a1")
          // A nudge up moves the node up on the screen.
          val before2 = canvas.svg
          key("ArrowUp")
          redrawn(canvas, before2, a).map: _ =>
            val a2 = centerOf(shapeOf(canvas.nodeGroup(a).get))
            check(math.abs(a2._2 - (a1._2 - grid * scale)) <= 1.5, s"Shift+Up did not move the node up on the screen: ${a1._2} -> ${a2._2}")

  // ------------------------------------------------------------ the release, with the layout animation on (as the app)

  /** The route of each arrow: its id, the `d` of its drawn path, and the `d` of its hit area. */
  private def routes(canvas: TestCanvas): Seq[(String, String, String)] =
    canvas.box
      .querySelectorAll("g.edge")
      .toSeq
      .collect { case g: dom.Element => g }
      .map: g =>
        val drawn = Option(g.querySelector("path:not(.edge-hit-area)")).map(_.getAttribute("d")).getOrElse("")
        val hit   = Option(g.querySelector("path.edge-hit-area")).map(_.getAttribute("d")).getOrElse("")
        (g.id, drawn, hit)

  /** A canvas of a free layout with the layout animation on, as the app has it. The body starts after the
    * animation of Freeze ends.
    */
  private def withAnimatedFreeCanvas(source: String, first: NodeId)(body: TestCanvas => Future[Unit]): Future[Unit] =
    withCanvas(source, first): canvas =>
      sizeLikeApp(canvas)
      check(canvas.state.animateLayoutChanges.now(), "precondition: the layout animation is off")
      check(!LayoutTransition.reducedMotion, "precondition: the test browser asks for reduced motion")
      freeze(canvas, first).flatMap(_ => Waits.after(600)).flatMap(_ => body(canvas))

  // Found in the desktop app on 2026-11-27 (a screen recording). During a drag, dotted lines follow the node, and
  // the arrows of the old drawing stay at their old routes. The release drew each arrow first at its OLD route, to
  // the place of the node before the drag, and then animated it to the new route. In the manual mode the person
  // puts each node, so the release must show the new drawing at once.
  test("the release of a drag in a free layout draws each arrow at its new route at once, with no animation"):
    withAnimatedFreeCanvas(dot, a): canvas =>
      val before = canvas.svg
      drag(canvas, d, 80, 40)()
      redrawn(canvas, before, a).map: _ =>
        // `redrawn` waits 30 ms after the new svg: the animation (300 ms) of a layout change runs at that time.
        val transitioning = canvas.svg.classList.contains(LayoutTransition.transitioningClass)
        val stale         = routes(canvas).filter((_, drawn, hit) => drawn != hit)
        check(
          !transitioning && stale.isEmpty,
          s"after the release, the arrows animate from their old routes: transitioning=$transitioning; arrows not at " +
            s"their new route: ${stale.map((id, drawn, hit) => s"$id drawn=${drawn.take(40)} hit=${hit.take(40)}").mkString("; ")}"
        )

  // Found in the same recording: after a drop near the top edge of the visible area, the whole drawing jumped up and
  // grew, and then it came back to the old view. In the manual mode the view must not change after a drop, not even
  // for a moment: the person zooms or pans when they want.
  test("a drop beyond the edge of the drawing keeps the view: each other node keeps its place and size on the screen"):
    withAnimatedFreeCanvas(dot, d): canvas =>
      val others = Seq(b, c, d)
      def boxes: Map[NodeId, (Double, Double, Double, Double)] =
        others
          .map: id =>
            val r = shapeOf(canvas.nodeGroup(id).get).getBoundingClientRect()
            id -> (r.left, r.top, r.width, r.height)
          .toMap
      def moved(boxes0: Map[NodeId, (Double, Double, Double, Double)], boxes1: Map[NodeId, (Double, Double, Double, Double)]) =
        others.filter: id =>
          val (l0, t0, w0, h0) = boxes0(id)
          val (l1, t1, w1, h1) = boxes1(id)
          math.abs(l1 - l0) > 1 || math.abs(t1 - t0) > 1 || math.abs(w1 - w0) > 1 || math.abs(h1 - h0) > 1
      def report(at: String, boxes0: Map[NodeId, (Double, Double, Double, Double)], boxes1: Map[NodeId, (Double, Double, Double, Double)]) =
        s"the view changed $at the drop: these nodes moved or changed size on the screen: " +
          moved(boxes0, boxes1).map(id => s"$id ${boxes0(id)} -> ${boxes1(id)}").mkString("; ")
      val boxes0 = boxes
      val before = canvas.svg
      // Up, past the top edge of the drawing and of the canvas: the drawing grows at the top.
      val top = shapeOf(canvas.nodeGroup(a).get).getBoundingClientRect().top - canvas.box.getBoundingClientRect().top
      drag(canvas, a, 40, -(top + 40))()
      redrawn(canvas, before, d)
        .flatMap: _ =>
          // Right after the new svg (`redrawn` waits 30 ms): the time of the jump in the recording.
          val early = boxes
          check(moved(boxes0, early).isEmpty, report("right after", boxes0, early))
          Waits.after(600)
        .map: _ =>
          val late = boxes
          check(moved(boxes0, late).isEmpty, report("after", boxes0, late))

  // ------------------------------------------------------------ the stand-in of an arrow during a drag

  /** A client point. */
  private type Pt = (Double, Double)

  /** The distance from `p` to the border of the client rect `r`, inside or outside it. */
  private def toBorder(p: Pt, r: dom.DOMRect): Double =
    val (x, y)  = p
    val inside  = x >= r.left && x <= r.right && y >= r.top && y <= r.bottom
    if inside then Seq(x - r.left, r.right - x, y - r.top, r.bottom - y).min
    else
      val dx = (r.left - x) max 0.0 max (x - r.right)
      val dy = (r.top - y) max 0.0 max (y - r.bottom)
      math.hypot(dx, dy)

  private def toClient(e: dom.Element, x: Double, y: Double): Pt =
    val m = e.asInstanceOf[scala.scalajs.js.Dynamic].getScreenCTM()
    val a = m.a.asInstanceOf[Double]; val b2 = m.b.asInstanceOf[Double]; val c2 = m.c.asInstanceOf[Double]
    val d2 = m.d.asInstanceOf[Double]; val e2 = m.e.asInstanceOf[Double]; val f = m.f.asInstanceOf[Double]
    (a * x + c2 * y + e2, b2 * x + d2 * y + f)

  /** The stand-in of an arrow: where its stroke starts, and the tip of its head (None with no head), in client
    * points. The stroke is a `path` or a `line`, in the stand-in or the stand-in itself. The head is a `polygon` in
    * the stand-in, or the marker at the end of the stroke.
    */
  private def standIn(link: dom.Element): (Pt, Option[Pt]) =
    val stroke =
      if link.tagName.equalsIgnoreCase("path") || link.tagName.equalsIgnoreCase("line") then link
      else Option(link.querySelector("path")).orElse(Option(link.querySelector("line"))).get
    def pointAt(length: Double): Pt =
      if stroke.tagName.equalsIgnoreCase("line") then
        val (x1, y1, x2, y2) = Seq("x1", "y1", "x2", "y2").map(stroke.getAttribute(_).toDouble) match
          case Seq(a1, b1, a2, b2) => (a1, b1, a2, b2)
          case _                   => (0.0, 0.0, 0.0, 0.0)
        if length == 0 then toClient(stroke, x1, y1) else toClient(stroke, x2, y2)
      else
        val p = stroke.asInstanceOf[scala.scalajs.js.Dynamic].getPointAtLength(length)
        toClient(stroke, p.x.asInstanceOf[Double], p.y.asInstanceOf[Double])
    val total =
      if stroke.tagName.equalsIgnoreCase("line") then 1.0
      else stroke.asInstanceOf[scala.scalajs.js.Dynamic].getTotalLength().asInstanceOf[Double]
    val start = pointAt(0)
    val polygonTip = Option(link.querySelector("polygon")).map: head =>
      val pts = head.getAttribute("points").trim.split("\\s+").toVector.filter(_.contains(',')).map: s =>
        val xy = s.split(',')
        toClient(head, xy(0).toDouble, xy(1).toDouble)
      pts.maxBy(p => math.hypot(p._1 - start._1, p._2 - start._2))
    val markerTip = Option(stroke.getAttribute("marker-end")).filter(_.nonEmpty).map(_ => pointAt(total))
    (start, polygonTip.orElse(markerTip))

  // Found in the same recording: during a drag, an arrow with one moved end became a dashed straight line between the
  // CENTERS of its two nodes, with no head. The person could not see it as the arrow. The stand-in must look like the
  // arrow: it goes from the border of the source to the border of the target, and it has its head at the target.
  test("during a drag, each arrow with one moved end shows as an arrow from border to border, with its head at the target"):
    val boxes = "digraph G {\n  node [shape=box]\n  a -> c\n  c -> d\n}"
    withFreeCanvas(boxes, a): canvas =>
      val problems = mutable.ArrayBuffer.empty[String]
      var count    = 0
      drag(canvas, c, 140, 30):
        val links = canvas.svg.querySelectorAll(s".${NodeDrag.LinkClass}").toSeq.collect { case e: dom.Element => e }
        count = links.length
        val rect = (id: NodeId) => shapeOf(canvas.nodeGroup(id).get).getBoundingClientRect()
        def box(r: dom.DOMRect) = f"${r.left}%.1f,${r.top}%.1f,${r.right}%.1f,${r.bottom}%.1f"
        // The arrow a -> c has the moved node at its target, and c -> d has it at its source.
        val expected = Seq((a, c), (c, d))
        val found = links.map(standIn)
        expected.foreach: (s, t) =>
          val matches = found.exists:
            case (start, Some(tip)) => toBorder(start, rect(s)) <= 2.5 && toBorder(tip, rect(t)) <= 2.5
            case _                  => false
          if !matches then
            val seen = found.map((st, tip) => s"start=$st tip=$tip").mkString("; ")
            problems += s"no stand-in of $s -> $t starts on the border of $s (${box(rect(s))}) and has its head " +
              s"tip on the border of $t (${box(rect(t))}): $seen"
      check(count == 2, s"the drag shows $count stand-ins, not one for each of the 2 arrows with one moved end")
      check(problems.isEmpty, problems.mkString("\n"))
      Future.unit

end NodeDragSpec
