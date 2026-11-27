package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.laminar.api.L.render
import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.{DiagramLayout, LayoutPoint}
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.viewer.components.NewNodeLabelDialog
import org.jpablo.graphexplorer.viewer.components.selection.MermaidSelectionStrategy
import org.jpablo.graphexplorer.viewer.components.svgCanvas.CanvasDropSpec.{filesTransfer, otherFile}
import org.jpablo.graphexplorer.viewer.domUtils.elementsFromPoint
import org.jpablo.graphexplorer.viewer.models.NodeId
import org.jpablo.graphexplorer.viewer.state.ManualLayoutOps
import org.jpablo.graphexplorer.viewer.utils.{TestHelpers, Waits}
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** Manual layout, part 3: in a free layout, a new node goes to a place that the person chooses (ADR 0004).
  *
  *   - A drop puts the new nodes at the drop point. Several items go in a column below the first item.
  *   - A paste, New node (`n`) and the toolbar put the new node at the pointer when the pointer is over the canvas.
  *     Else the node goes to the center of the visible part of the canvas.
  *   - A double-click on the empty canvas adds a node at that point.
  *
  * The center of the new node is on the grid of 9 points. The node and its position are one step of the history. In
  * an automatic diagram, the layout places the new node, as before, and no position is stored.
  *
  * The tests send real events to the rendered canvas, and they measure the new drawing.
  */
class NewNodePlacementSpec extends FunSuite with TestHelpers with LoggedChecks with CanvasFixture:
  import NewNodePlacementSpec.*

  def checkPrefix = "NewNodePlacementSpec"

  // Through the Scala.js test bridge and the sbt client, a munit failure arrives with no message. An AssertionError
  // keeps its message.
  override def check(condition: Boolean, clue: => String): Unit =
    if !condition then throw AssertionError(s"[$checkPrefix] $clue")

  def canvasDiagramId = "new-node-placement-spec"

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
  private val c = NodeId("c")

  /** The spacing of the snap grid, in layout points. */
  private val grid = ManualLayoutOps.GridPt

  // ------------------------------------------------------------ the canvas

  private def layoutOf(canvas: TestCanvas): DiagramLayout = canvas.state.layout.now()

  private def nodeIds(canvas: TestCanvas): Set[NodeId] = canvas.state.fullGraphNow().nodeIds

  /** Wait for a new svg that shows the node `first`, and for the frame after it. */
  private def redrawn(canvas: TestCanvas, before: dom.svg.SVG, first: NodeId): Future[Unit] =
    Waits
      .settle(!(canvas.svg eq before) && canvas.nodeGroup(first).isDefined, timeoutMs = 5000)
      .flatMap(_ => Waits.after(30))
      .map(_ => check(!(canvas.svg eq before), "the canvas did not draw the diagram again"))

  /** A canvas of a free layout of `dot`, with no layout animation, so that each measure is at the final place. */
  private def withFreeDot(body: TestCanvas => Future[Unit]): Future[Unit] =
    withCanvas(dot, a): canvas =>
      sizeLikeApp(canvas.box)
      canvas.state.animateLayoutChanges.set(false)
      canvas.state.promptLabelBeforeNewNode.set(false)
      val before = canvas.svg
      canvas.state.history.markGesture()
      canvas.state.freezeLayout()
      check(layoutOf(canvas).hasPositions, "precondition: Freeze stored no position")
      redrawn(canvas, before, a).flatMap(_ => body(canvas))

  private def mermaidNode(canvas: TestCanvas, id: NodeId): Option[dom.Element] =
    canvas.svg.querySelectorAll(MermaidSelectionStrategy.nodeSelector).toSeq.find(e => MermaidSelectionStrategy.extractNodeId(e) == id)

  /** A canvas of a free layout of a Mermaid flowchart. The free layout draws with Graphviz. */
  private def withFreeMermaid(body: TestCanvas => Future[Unit]): Future[Unit] =
    withCanvasWhen(mermaid, "the Mermaid drawing shows the node B")(cv => mermaidNode(cv, NodeId("B")).isDefined): canvas =>
      sizeLikeApp(canvas.box)
      canvas.state.animateLayoutChanges.set(false)
      canvas.state.promptLabelBeforeNewNode.set(false)
      val before = canvas.svg
      canvas.state.history.markGesture()
      canvas.state.placeNodes(
        Map(NodeId("A") -> LayoutPoint(0, 0), NodeId("B") -> LayoutPoint(180, 0), NodeId("C") -> LayoutPoint(360, 0))
      )
      redrawn(canvas, before, NodeId("B")).flatMap(_ => body(canvas))

  /** The client point `(dx, dy)` pixels from the center of the shape of the node `id`. The point must be on the svg,
    * on no node and on no arrow.
    */
  private def freePointBeside(canvas: TestCanvas, id: NodeId, dx: Double, dy: Double): (Double, Double) =
    val (x0, y0) = centerOf(shapeOf(canvas.nodeGroup(id).get))
    val p        = (x0 + dx, y0 + dy)
    val under    = dom.document.elementsFromPoint(p._1, p._2)
    check(under.exists(_ eq canvas.svg), s"precondition: the point $p is not on the svg")
    check(under.forall(e => e.closest("g.node, g.edge") == null), s"precondition: the point $p is on an element")
    p

  /** A client point on the svg, on no node and on no arrow, near the node `id`. */
  private def someFreePoint(canvas: TestCanvas, id: NodeId): (Double, Double) =
    val (x0, y0) = centerOf(shapeOf(canvas.nodeGroup(id).get))
    val candidates =
      for
        k        <- (40 to 200 by 20).toVector
        (dx, dy) <- Vector((k, 0), (-k, 0), (0, k), (0, -k), (k, k), (-k, -k))
      yield (x0 + dx, y0 + dy)
    val free = candidates.find: (x, y) =>
      val under = dom.document.elementsFromPoint(x, y)
      under.exists(_ eq canvas.svg) && under.forall(e => e.closest("g.node, g.edge") == null)
    check(free.isDefined, s"precondition: no free point near the node $id")
    free.get

  /** The layout point under the client point `p`, from the stored place and the drawn place of the pinned node `ref`. */
  private def layoutPointAt(canvas: TestCanvas, ref: NodeId, p: (Double, Double)): LayoutPoint =
    val g        = canvas.nodeGroup(ref).get
    val scale    = pxPerPoint(g)
    val (cx, cy) = centerOf(shapeOf(g))
    val stored   = layoutOf(canvas).point(ref.value).get
    LayoutPoint(stored.x + (p._1 - cx) / scale, stored.y - (p._2 - cy) / scale)

  private def onGrid(p: LayoutPoint): Boolean =
    def ok(v: Double) = math.abs(v / grid - math.round(v / grid)) < 1e-6
    ok(p.x) && ok(p.y)

  /** The stored place of the new node `id` is on the grid, and within half a step of `expected` on each axis. */
  private def placedNear(canvas: TestCanvas, id: NodeId, expected: LayoutPoint): LayoutPoint =
    val stored = layoutOf(canvas).point(id.value)
    check(stored.isDefined, s"the layout has no position for the new node $id: ${layoutOf(canvas)}")
    val p = stored.get
    check(onGrid(p), s"the position of $id is not on the grid: $p")
    check(
      math.abs(p.x - expected.x) <= grid / 2 + 0.01 && math.abs(p.y - expected.y) <= grid / 2 + 0.01,
      s"the position of $id is $p, and the point is $expected"
    )
    p

  /** After the render, the center of the new node `id` is within one grid step of the client point `p`. */
  private def drawnAt(canvas: TestCanvas, id: NodeId, p: (Double, Double)): Unit =
    val g = canvas.nodeGroup(id)
    check(g.isDefined, s"the canvas does not show the new node $id")
    val scale    = pxPerPoint(g.get)
    val (cx, cy) = centerOf(shapeOf(g.get))
    val limit    = grid * scale
    check(
      math.abs(cx - p._1) <= limit && math.abs(cy - p._2) <= limit,
      s"the new node $id is drawn at ($cx, $cy), and the point is $p (limit $limit px)"
    )

  /** Wait for the drawing of the node `id`, with the layout animation off, and for the frame after it. */
  private def drawnNode(canvas: TestCanvas, id: NodeId): Future[Unit] =
    Waits
      .settle(canvas.nodeGroup(id).isDefined, timeoutMs = 5000)
      .flatMap(_ => Waits.after(30))
      .map(_ => check(canvas.nodeGroup(id).isDefined, s"the canvas does not show the new node $id"))

  /** One undo removes the new node `id` and its position. The layout changes at once, and the graph after the parse
    * of the old text.
    */
  private def undoRemoves(canvas: TestCanvas, id: NodeId): Future[Unit] =
    check(canvas.state.history.canUndoNow, "the change is not a step of the history")
    canvas.state.history.undo()
    check(layoutOf(canvas).point(id.value).isEmpty, s"one undo did not remove the position: ${layoutOf(canvas)}")
    Waits.settle(!nodeIds(canvas).contains(id)).map: _ =>
      check(!nodeIds(canvas).contains(id), s"one undo did not remove the node $id")

  private def container(canvas: TestCanvas): dom.html.Element =
    canvas.box.querySelector("#canvas-container").asInstanceOf[dom.html.Element]

  /** A drag of `transfer` that the person drops at the client point `p`. */
  private def dropAt(canvas: TestCanvas, transfer: js.Dynamic, p: (Double, Double)): Unit =
    def send(kind: String): Unit =
      val init = js.Dynamic.literal(dataTransfer = transfer, bubbles = true, cancelable = true, clientX = p._1, clientY = p._2)
      val ev   = js.Dynamic.newInstance(js.Dynamic.global.DragEvent)(kind, init).asInstanceOf[dom.Event]
      dom.document.elementFromPoint(p._1, p._2).dispatchEvent(ev)
    send("dragenter")
    send("dragover")
    send("drop")

  private def paste(canvas: TestCanvas, text: String): Unit =
    val init = js.Dynamic.literal(clipboardData = CanvasPasteSpec.textTransfer(text), bubbles = true, cancelable = true)
    val ev   = js.Dynamic.newInstance(js.Dynamic.global.ClipboardEvent)("paste", init).asInstanceOf[dom.Event]
    container(canvas).dispatchEvent(ev)

  /** The pointer goes to the client point `p` over the canvas, with no button down. */
  private def pointerTo(p: (Double, Double)): Unit =
    dom.document.elementFromPoint(p._1, p._2).dispatchEvent(mouse("mousemove", p._1, p._2, buttons = 0))

  /** The pointer leaves the canvas. */
  private def pointerOut(canvas: TestCanvas): Unit =
    val r = container(canvas).getBoundingClientRect()
    container(canvas).dispatchEvent(mouse("mouseleave", r.right + 20, r.top - 20, buttons = 0, bubbles = false))

  private def key(canvas: TestCanvas, name: String): Unit =
    val init = new dom.KeyboardEventInit {}
    init.key = name
    init.bubbles = true
    init.cancelable = true
    // A browser sends the key to the focused canvas. The history marks a gesture for a key.
    container(canvas).dispatchEvent(new dom.KeyboardEvent("keydown", init))

  /** A double-click at the client point `p`, as a browser sends it. */
  private def doubleClickAt(p: (Double, Double)): Unit =
    val target = dom.document.elementFromPoint(p._1, p._2)
    for detail <- 1 to 2 do
      pressAt(target, p)
      target.dispatchEvent(mouse("mouseup", p._1, p._2, buttons = 0, detail = detail))
      target.dispatchEvent(mouse("click", p._1, p._2, buttons = 0, detail = detail))
    target.dispatchEvent(mouse("dblclick", p._1, p._2, buttons = 0, detail = 2))

  /** The center of the visible part of the canvas: the box of the canvas, inside the window. */
  private def visibleCenter(canvas: TestCanvas): (Double, Double) =
    val r      = container(canvas).getBoundingClientRect()
    val left   = math.max(r.left, 0)
    val top    = math.max(r.top, 0)
    val right  = math.min(r.right, dom.window.innerWidth.toDouble)
    val bottom = math.min(r.bottom, dom.window.innerHeight.toDouble)
    if right > left && bottom > top then ((left + right) / 2, (top + bottom) / 2)
    else (r.left + r.width / 2, r.top + r.height / 2)

  /** The one new node of the canvas, after a change that added one node. */
  private def oneNew(canvas: TestCanvas, before: Set[NodeId]): NodeId =
    val added = nodeIds(canvas) -- before
    check(added.size == 1, s"the change did not add one node: $added")
    added.head

  // ------------------------------------------------------------ a drop

  test("DOT free layout: a drop of text puts the center of the new node at the drop point"):
    withFreeDot: canvas =>
      val p        = freePointBeside(canvas, c, 90, 0)
      val expected = layoutPointAt(canvas, a, p)
      dropAt(canvas, CanvasPasteSpec.textTransfer("Hello world"), p)
      val id = NodeId("hello_world")
      Waits.settle(nodeIds(canvas).contains(id)).flatMap: _ =>
        check(nodeIds(canvas).contains(id), "the drop did not add a node")
        placedNear(canvas, id, expected)
        drawnNode(canvas, id).map(_ => drawnAt(canvas, id, p))

  test("Mermaid free layout: a drop of a file puts the center of the new node at the drop point"):
    withFreeMermaid: canvas =>
      val p        = freePointBeside(canvas, NodeId("B"), 0, 80)
      val expected = layoutPointAt(canvas, NodeId("A"), p)
      dropAt(canvas, filesTransfer(otherFile("report.pdf", "application/pdf")), p)
      val id = NodeId("report")
      Waits.settle(nodeIds(canvas).contains(id)).flatMap: _ =>
        check(nodeIds(canvas).contains(id), s"the drop did not add a node: ${canvas.state.sourceText.now()}")
        placedNear(canvas, id, expected)
        drawnNode(canvas, id).map(_ => drawnAt(canvas, id, p))

  test("a drop of several files puts the first at the point, and the others below it with no overlap"):
    withFreeDot: canvas =>
      val p        = freePointBeside(canvas, c, 90, 0)
      val expected = layoutPointAt(canvas, a, p)
      dropAt(
        canvas,
        filesTransfer(
          otherFile("one.pdf", "application/pdf"),
          otherFile("two with a long name.pdf", "application/pdf"),
          otherFile("three.pdf", "application/pdf")
        ),
        p
      )
      val ids = List(NodeId("one"), NodeId("two_with_a"), NodeId("three"))
      Waits.settle(ids.forall(nodeIds(canvas).contains)).flatMap: _ =>
        check(ids.forall(nodeIds(canvas).contains), s"the drop did not add the nodes: ${nodeIds(canvas)}")
        val first  = placedNear(canvas, ids.head, expected)
        val places = ids.map(id => layoutOf(canvas).point(id.value))
        check(places.forall(_.isDefined), s"a new node has no position: $places")
        val ps = places.flatten
        check(ps.forall(onGrid), s"a position is not on the grid: $ps")
        check(ps.forall(_.x == first.x), s"the new nodes are not in one column: $ps")
        check(ps.zip(ps.tail).forall((p0, p1) => p1.y < p0.y), s"each next node is not below the node before it: $ps")
        Future.sequence(ids.map(drawnNode(canvas, _))).map: _ =>
          drawnAt(canvas, ids.head, p)
          val boxes = ids.map(id => id -> shapeOf(canvas.nodeGroup(id).get).getBoundingClientRect())
          for
            (i, r0) <- boxes
            (j, r1) <- boxes
            if i.value < j.value
          do
            val overlap = r0.left < r1.right && r1.left < r0.right && r0.top < r1.bottom && r1.top < r0.bottom
            check(!overlap, s"the new nodes $i and $j overlap: (${r0.left}, ${r0.top}, ${r0.width}, ${r0.height}) and (${r1.left}, ${r1.top}, ${r1.width}, ${r1.height})")

  test("one undo removes the dropped node and its position"):
    withFreeDot: canvas =>
      val frozen = layoutOf(canvas)
      val source = canvas.state.sourceText.now()
      val p      = freePointBeside(canvas, c, 90, 0)
      dropAt(canvas, CanvasPasteSpec.textTransfer("Hello world"), p)
      val id = NodeId("hello_world")
      Waits.settle(nodeIds(canvas).contains(id)).flatMap: _ =>
        check(layoutOf(canvas).point(id.value).isDefined, s"the drop stored no position: ${layoutOf(canvas)}")
        undoRemoves(canvas, id).flatMap: _ =>
          check(canvas.state.sourceText.now() == source, s"one undo did not put the source back:\n${canvas.state.sourceText.now()}")
          check(layoutOf(canvas) == frozen, s"one undo did not remove the position: ${layoutOf(canvas)}")
          check(canvas.state.history.canRedoNow, "the undo removed the redo")
          canvas.state.history.redo()
          Waits.settle(nodeIds(canvas).contains(id)).map: _ =>
            check(nodeIds(canvas).contains(id) && layoutOf(canvas).point(id.value).isDefined, "redo did not put the node and its position back")

  // ------------------------------------------------------------ a paste

  test("a paste with the pointer over the canvas puts the new node at the pointer"):
    withFreeDot: canvas =>
      val p        = freePointBeside(canvas, a, -90, 30)
      val expected = layoutPointAt(canvas, a, p)
      pointerTo(p)
      canvas.state.history.markGesture()
      paste(canvas, "Hello world")
      val id = NodeId("hello_world")
      Waits.settle(nodeIds(canvas).contains(id)).flatMap: _ =>
        placedNear(canvas, id, expected)
        drawnNode(canvas, id).map(_ => drawnAt(canvas, id, p))

  test("a paste with the pointer out of the canvas puts the new node at the center of the visible canvas"):
    withFreeDot: canvas =>
      pointerTo(freePointBeside(canvas, a, -90, 30))
      pointerOut(canvas)
      val center   = visibleCenter(canvas)
      val expected = layoutPointAt(canvas, a, center)
      paste(canvas, "Hello world")
      val id = NodeId("hello_world")
      Waits.settle(nodeIds(canvas).contains(id)).flatMap: _ =>
        placedNear(canvas, id, expected)
        drawnNode(canvas, id).map(_ => drawnAt(canvas, id, center))

  // ------------------------------------------------------------ New node

  test("New node (n) puts the new node at the pointer"):
    withFreeDot: canvas =>
      val p        = freePointBeside(canvas, c, 90, 0)
      val expected = layoutPointAt(canvas, a, p)
      val before   = nodeIds(canvas)
      pointerTo(p)
      key(canvas, "n")
      Waits.settle(nodeIds(canvas).size == before.size + 1).flatMap: _ =>
        val id = oneNew(canvas, before)
        placedNear(canvas, id, expected)
        drawnNode(canvas, id).flatMap: _ =>
          drawnAt(canvas, id, p)
          undoRemoves(canvas, id)

  test("New node (n) with the label dialog puts the new node at the pointer of the key"):
    withFreeDot: canvas =>
      canvas.state.promptLabelBeforeNewNode.set(true)
      val dialog   = render(canvas.box, NewNodeLabelDialog(canvas.state))
      val p        = freePointBeside(canvas, c, 90, 0)
      val expected = layoutPointAt(canvas, a, p)
      val before   = nodeIds(canvas)
      pointerTo(p)
      key(canvas, "n")
      check(canvas.state.pendingNewNodeV.now().isDefined, "n did not open the label dialog")
      check(nodeIds(canvas) == before, "n added a node before the dialog")
      // The pointer goes to the dialog. The node goes to the place of the key.
      pointerOut(canvas)
      def okButton = canvas.box.querySelectorAll("#new-node-label-dialog button").toSeq.find(_.textContent.trim == "Ok")
      Waits
        .settle(okButton.isDefined, timeoutMs = 3000)
        .flatMap: _ =>
          check(okButton.isDefined, "the dialog has no Ok button")
          val ok = okButton.get
          val r  = ok.getBoundingClientRect()
          pressAt(ok, (r.left + r.width / 2, r.top + r.height / 2))
          ok.asInstanceOf[dom.html.Element].click()
          Waits.settle(nodeIds(canvas).size == before.size + 1)
        .flatMap: _ =>
          val id = oneNew(canvas, before)
          placedNear(canvas, id, expected)
          undoRemoves(canvas, id)
        .transform: outcome =>
          dialog.unmount()
          outcome

  test("Mermaid free layout: New node (n) puts the new node at the pointer"):
    withFreeMermaid: canvas =>
      val p        = freePointBeside(canvas, NodeId("B"), 0, -80)
      val expected = layoutPointAt(canvas, NodeId("A"), p)
      val before   = nodeIds(canvas)
      pointerTo(p)
      key(canvas, "n")
      Waits.settle(nodeIds(canvas).size == before.size + 1).flatMap: _ =>
        val id = oneNew(canvas, before)
        placedNear(canvas, id, expected)
        drawnNode(canvas, id).map(_ => drawnAt(canvas, id, p))

  // ------------------------------------------------------------ a double-click

  test("a double-click on the empty canvas of a free layout adds a node at that point"):
    withFreeDot: canvas =>
      val p        = freePointBeside(canvas, c, 90, 0)
      val expected = layoutPointAt(canvas, a, p)
      val before   = nodeIds(canvas)
      doubleClickAt(p)
      Waits.settle(nodeIds(canvas).size == before.size + 1).flatMap: _ =>
        val id = oneNew(canvas, before)
        placedNear(canvas, id, expected)
        drawnNode(canvas, id).flatMap: _ =>
          drawnAt(canvas, id, p)
          undoRemoves(canvas, id).map(_ => check(nodeIds(canvas) == before, "one undo did not remove the new node"))

  test("a double-click on a node of a free layout still edits its label, and adds no node"):
    withFreeDot: canvas =>
      canvas.state.promptLabelBeforeNewNode.set(true)
      val before = nodeIds(canvas)
      doubleClickAt(centerOf(shapeOf(canvas.nodeGroup(a).get)))
      Waits.after(100).map: _ =>
        check(nodeIds(canvas) == before, s"a double-click on a node added a node: ${nodeIds(canvas) -- before}")
        check(canvas.state.pendingNewNodeV.now().isEmpty, "a double-click on a node opened the new node dialog")

  test("a double-click on the empty canvas of an automatic diagram adds no node"):
    withCanvas(dot, a): canvas =>
      sizeLikeApp(canvas.box)
      canvas.state.promptLabelBeforeNewNode.set(false)
      val before = nodeIds(canvas)
      doubleClickAt(someFreePoint(canvas, c))
      Waits.after(100).map: _ =>
        check(nodeIds(canvas) == before, s"a double-click on an automatic diagram added a node: ${nodeIds(canvas) -- before}")
        check(!layoutOf(canvas).hasPositions, s"a double-click stored positions: ${layoutOf(canvas)}")

  // ------------------------------------------------------------ an automatic diagram

  test("in an automatic diagram, a drop, a paste and n add nodes and store no position"):
    withCanvas(dot, a): canvas =>
      sizeLikeApp(canvas.box)
      canvas.state.promptLabelBeforeNewNode.set(false)
      val p = someFreePoint(canvas, c)
      pointerTo(p)
      dropAt(canvas, CanvasPasteSpec.textTransfer("Dropped text"), p)
      canvas.state.history.markGesture()
      paste(canvas, "Pasted text")
      val before = nodeIds(canvas)
      key(canvas, "n")
      Waits
        .settle(Set(NodeId("dropped_text"), NodeId("pasted_text")).subsetOf(nodeIds(canvas)) && nodeIds(canvas).size == before.size + 1)
        .map: _ =>
          check(Set(NodeId("dropped_text"), NodeId("pasted_text")).subsetOf(nodeIds(canvas)), s"the nodes: ${nodeIds(canvas)}")
          check(nodeIds(canvas).size == before.size + 1, "n did not add a node")
          check(!layoutOf(canvas).hasPositions, s"an automatic diagram stored positions: ${layoutOf(canvas)}")

  // ------------------------------------------------------------ the arrangement

  test("the column of new nodes: the first at the point on the grid, each next one below with one step between"):
    val places = ManualLayoutOps.columnPlaces(LayoutPoint(10, 20), List((54.0, 36.0), (100.0, 50.0), (54.0, 36.0)))
    check(places.head == LayoutPoint(9, 18), s"the first place: ${places.head}")
    check(places.forall(onGrid), s"a place is not on the grid: $places")
    check(places.forall(_.x == 9), s"the places are not in one column: $places")
    // The gap between two boxes is at least one grid step.
    val heights = List(36.0, 50.0, 36.0)
    for i <- 1 until places.size do
      val gap = (places(i - 1).y - heights(i - 1) / 2) - (places(i).y + heights(i) / 2)
      check(gap >= grid - 1e-9 && gap < 2 * grid, s"the gap between the boxes $i and ${i - 1}: $gap ($places)")

end NewNodePlacementSpec

object NewNodePlacementSpec:

  def mouse(
      kind: String,
      x: Double,
      y: Double,
      buttons: Int = 1,
      detail: Int = 1,
      bubbles: Boolean = true
  ): dom.MouseEvent =
    val init = new dom.MouseEventInit {}
    init.clientX = x
    init.clientY = y
    init.button = 0
    init.buttons = buttons
    init.detail = detail
    init.bubbles = bubbles
    init.cancelable = true
    dom.MouseEvent(kind, init)

  /** A press as a browser sends it: `pointerdown` (the history marks a gesture), then `mousedown`. */
  def pressAt(target: dom.Element, p: (Double, Double)): Unit =
    val init = new dom.PointerEventInit {}
    init.clientX = p._1
    init.clientY = p._2
    init.button = 0
    init.buttons = 1
    init.bubbles = true
    target.dispatchEvent(new dom.PointerEvent("pointerdown", init))
    target.dispatchEvent(mouse("mousedown", p._1, p._2))

  def centerOf(e: dom.Element): (Double, Double) =
    val r = e.getBoundingClientRect()
    (r.left + r.width / 2, r.top + r.height / 2)

  /** The shape of a node group of Graphviz. */
  def shapeOf(g: dom.Element): dom.Element =
    Option(g.querySelector("ellipse")).orElse(Option(g.querySelector("polygon"))).orElse(Option(g.querySelector("rect"))).get

  /** Client pixels for one layout point, from the shape of a Graphviz node: its width in points and its client width. */
  def pxPerPoint(g: dom.Element): Double =
    val shape = shapeOf(g)
    val points =
      if shape.tagName.equalsIgnoreCase("ellipse") then 2 * shape.getAttribute("rx").toDouble
      else
        val xs = shape.getAttribute("points").trim.split("\\s+").toVector.filter(_.contains(',')).map(_.split(',')(0).toDouble)
        xs.max - xs.min
    shape.getBoundingClientRect().width / points

  /** Give the svg the size of the canvas, as the app stylesheet does. The test page has no app stylesheet: the svg
    * then gets the height of its viewBox at the width of the box, and it overflows the box. (See `FitTestCanvas`.)
    */
  def sizeLikeApp(box: dom.Element): Unit =
    val rules = dom.document.createElement("style")
    rules.textContent =
      ".floating-toolbar { position: absolute; } " +
        "#canvas-container { width: 100%; height: 100%; } " +
        "#canvas-container > svg { display: block; width: 100%; height: 100%; }"
    box.appendChild(rules)

end NewNodePlacementSpec
