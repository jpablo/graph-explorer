package org.jpablo.graphexplorer.viewer.state.mouseActions

import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.viewer.components.svgCanvas.{CanvasFixture, TestCanvas}
import org.jpablo.graphexplorer.viewer.models.{Arrow, ElementId, GroupId, NodeId}
import org.jpablo.graphexplorer.viewer.state.mouseActions.MouseAction.MoveArrowEndpointAction
import org.jpablo.graphexplorer.viewer.utils.{TestHelpers, Waits}
import org.jpablo.graphexplorer.viewer.domUtils.elementsFromPoint
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/** The endpoint controls of a selected arrow, on a real canvas.
  *
  * `MoveArrowEndpointOps` got the two end nodes of the arrow from the text of
  * the arrow id (`Arrow.fromArrowId`). The id holds more than the two node ids:
  * a port (`a:f0->b/1`), and the text of a node id, which can hold `->` or
  * outer spaces. Thus the parse gave a wrong end node, or no arrow. Now the
  * canvas finds the arrow by its id in the visible graph.
  */
class MoveArrowEndpointSpec extends FunSuite with TestHelpers with LoggedChecks with CanvasFixture:

  def checkPrefix = "MoveArrowEndpointSpec"

  def canvasDiagramId = "move-arrow-endpoint-spec"

  override def munitFixtures = List(mockStorageFixture())

  extension (canvas: TestCanvas)

    /** The endpoint disk of the selected arrow, for one end. */
    private def disk(endpoint: ArrowEndpoint): Option[dom.svg.G] =
      Option(canvas.box.querySelector(s"g.edge-endpoint-disk-$endpoint")).collect:
        case g: dom.svg.G => g

    /** The arrow of the diagram from `source` to `target`. */
    private def arrow(source: NodeId, target: NodeId): Option[Arrow] =
      canvas.state.fullGraphNow().arrows.values.find(a => a.source == source && a.target == target)

  /** Wait until the canvas shows the two endpoint disks of the selected arrow. */
  private def waitForDisks(canvas: TestCanvas): Future[Unit] =
    Waits.settle(canvas.disk(ArrowEndpoint.source).isDefined && canvas.disk(ArrowEndpoint.target).isDefined, timeoutMs = 3000)

  /** Press the button on the `endpoint` disk of the selected arrow `arrow`. */
  private def startDrag(canvas: TestCanvas, arrow: Arrow, endpoint: ArrowEndpoint): Unit =
    val disk = canvas.disk(endpoint)
    check(disk.isDefined, s"precondition: the selected arrow ${arrow.id} has no $endpoint endpoint disk")
    val (dx, dy) = center(disk.get)
    disk.get.dispatchEvent(mouse("mousedown", dx, dy))

  /** A real mouse event at the client point `(x, y)`, with the left button
    * down.
    */
  private def mouse(kind: String, x: Double, y: Double): dom.MouseEvent =
    val init = new dom.MouseEventInit {}
    init.clientX = x
    init.clientY = y
    init.button = 0
    init.buttons = 1
    init.bubbles = true
    init.cancelable = true
    dom.MouseEvent(kind, init)

  private def center(e: dom.Element): (Double, Double) =
    val r = e.getBoundingClientRect()
    (r.left + r.width / 2, r.top + r.height / 2)

  /** True when the point `p` is in the client box of `e`, with a margin of
    * `margin` pixels on each side.
    */
  private def near(p: (Double, Double), e: dom.Element, margin: Double = 8): Boolean =
    val r = e.getBoundingClientRect()
    p._1 >= r.left - margin && p._1 <= r.right + margin && p._2 >= r.top - margin && p._2 <= r.bottom + margin

  /** One diagram with one arrow, and the end of that arrow that the test
    * moves.
    *
    *  - `dot`: the diagram.
    *  - `source`, `target`: the node ids of the two ends of the arrow.
    *  - `endpoint`: the end that the test moves.
    *  - `grip`: the client point in the end node where the pointer goes, as
    *    fractions of the width and the height of the node box.
    */
  private final case class Case(
      what:     String,
      dot:      String,
      source:   NodeId,
      target:   NodeId,
      endpoint: ArrowEndpoint,
      grip:     (Double, Double) = (0.5, 0.5)
  ):
    def endNode: NodeId = if endpoint.isSource then source else target

  // A record node `a` with the cells `f0` and `f1`. The arrow starts at the
  // cell `f0`, and its id is `a:f0->b/<seq>`. The parse of the id gave the
  // source `a:f0`, and not `a`. The grip is in the left cell (`f0`): that is
  // the cell where the arrow starts now.
  private val fromRecordPort = Case(
    "an arrow from a record port",
    """digraph { a [shape=record, label="<f0> left|<f1> right"]; a:f0 -> b }""",
    source = NodeId("a"),
    target = NodeId("b"),
    endpoint = ArrowEndpoint.source,
    grip = (0.25, 0.5)
  )

  // The id is `a->x->y/<seq>`. The regex `(.+)->(.+)/(\d+)` of the parse split
  // it at the last `->`, and gave the source `a->x` and the target `y`. The layout
  // goes from left to right. With the default layout (top to bottom), the
  // target node is below the window, and the browser finds no element there.
  private val toIdWithArrowText = Case(
    "an arrow to a node whose id holds '->'",
    """digraph { rankdir=LR; a -> "x->y" }""",
    source = NodeId("a"),
    target = NodeId("x->y"),
    endpoint = ArrowEndpoint.target
  )

  // The id is ` a->b/<seq>`. The parse trimmed the source, and gave `a`.
  private val fromIdWithOuterSpace = Case(
    "an arrow from a node whose id starts with a space",
    """digraph { " a" -> b }""",
    source = NodeId(" a"),
    target = NodeId("b"),
    endpoint = ArrowEndpoint.source
  )

  // The id is ` ->b/<seq>`. The parse trimmed the source to "", and then gave
  // no arrow.
  private val fromBlankId = Case(
    "an arrow from a node whose id is one space",
    """digraph { " " -> b }""",
    source = NodeId(" "),
    target = NodeId("b"),
    endpoint = ArrowEndpoint.source
  )

  /** Select the arrow of `c`, and wait for its two endpoint disks. */
  private def selectArrow(canvas: TestCanvas, c: Case): Future[Arrow] =
    val arrow = canvas.arrow(c.source, c.target)
    check(arrow.isDefined, s"precondition: the diagram has no arrow ${c.source} -> ${c.target}: ${canvas.state.fullGraphNow().arrows.keys}")
    canvas.state.selection.set2(arrow.get.id)
    waitForDisks(canvas).map(_ => arrow.get)

  // ---------------------------------------------------------------------------
  // The endpoint controls of a selected arrow
  // (`MoveArrowEndpointOps.handleArrowEndpointControl`).
  // ---------------------------------------------------------------------------

  // A selected arrow shows one disk at each end. The canvas makes the disks
  // only when it finds the arrow of the edge.
  // Failure (with the parse of the id): for the blank id, the parse gave no
  // arrow, and the canvas showed no disk.
  // The other three cases passed with the parse too. The parse gave an arrow
  // with a wrong end node, but `ArrowEndpointControl` does not use the node
  // element: the disk gets its position from the layout of the arrow
  // (`edgePositions`). These cases make sure that the disks stay at the ends.
  for c <- Seq(fromRecordPort, toIdWithArrowText, fromIdWithOuterSpace, fromBlankId) do
    test(s"a selected arrow shows an endpoint disk at each end: ${c.what}"):
      withCanvas(c.dot, c.source): canvas =>
        selectArrow(canvas, c).map: arrow =>
          check(canvas.state.selection.now().contains(arrow.id), s"precondition: the arrow ${arrow.id} is not selected")
          for (endpoint, node) <- Seq(ArrowEndpoint.source -> c.source, ArrowEndpoint.target -> c.target) do
            val disk = canvas.disk(endpoint)
            check(disk.isDefined, s"the selected arrow ${arrow.id} has no $endpoint endpoint disk")
            val group = canvas.nodeGroup(node)
            check(group.isDefined, s"precondition: the canvas does not show the node $node")
            check(
              near(center(disk.get), group.get),
              s"the $endpoint disk of ${arrow.id} is not at the node $node: disk=${center(disk.get)}"
            )

  // ---------------------------------------------------------------------------
  // The drag of an endpoint over its own end node
  // (`MoveArrowEndpointOps.onMoveArrowSourceAction`).
  // ---------------------------------------------------------------------------

  // A person drags an endpoint disk, and the pointer is over the node where
  // that end of the arrow is now (for a port, over the cell of that port).
  // That is not a new end, so the canvas keeps only the arrow in the
  // selection.
  // Failure (with the parse of the id): the parse gave a different node, and
  // the canvas also selected the node under the pointer as a new end.
  for c <- Seq(fromRecordPort, toIdWithArrowText, fromIdWithOuterSpace) do
    test(s"a drag of an endpoint over its own end node selects no new end: ${c.what}"):
      withCanvas(c.dot, c.source): canvas =>
        selectArrow(canvas, c).flatMap: arrow =>
          startDrag(canvas, arrow, c.endpoint)
          val group = canvas.nodeGroup(c.endNode)
          check(group.isDefined, s"precondition: the canvas does not show the node ${c.endNode}")
          val r = group.get.getBoundingClientRect()
          val (px, py) = (r.left + r.width * c.grip._1, r.top + r.height * c.grip._2)
          check(
            dom.document.elementsFromPoint(px, py).exists(e => group.get.contains(e)),
            s"precondition: the client point ($px, $py) is not over the node ${c.endNode}"
          )
          canvas.svg.dispatchEvent(mouse("mousemove", px, py))
          afterMicrotasks:
            check(
              canvas.state.mouseAction.now() match
                case a: MoveArrowEndpointAction => a.endpoint == c.endpoint && !a.rect.isEmpty
                case _                          => false
              ,
              s"precondition: the drag of the ${c.endpoint} disk did not start: ${canvas.state.mouseAction.now().name}"
            )
            same(
              canvas.state.selection.now().ids.toSet[ElementId],
              Set[ElementId](arrow.id),
              s"the pointer is over the ${c.endpoint} node ${c.endNode} of ${arrow.id}, but the canvas selects a new end"
            )

  // ---------------------------------------------------------------------------
  // The drop of an endpoint on a different cell of the same record
  // (`onMoveArrowSourceAction` and `handleMoveArrowStartMouseUp`).
  // ---------------------------------------------------------------------------

  // The arrow starts at the cell `f0` of the record `a`. A person drags the
  // source disk to the cell `f1` of `a`, and releases the button there. That
  // is the same node, but a different cell, thus a new end: the arrow must
  // start at `f1` after the drop.
  // This test passed with the parse of the id too.
  // Failure: the "same node" check ignores the pointer over `a`, and the drop
  // does not move the port.
  test("a drop of an endpoint on a different cell of the same record moves the port"):
    val c = fromRecordPort.copy(grip = (0.75, 0.5))
    withCanvas(c.dot, c.source): canvas =>
      selectArrow(canvas, c).flatMap: arrow =>
        check(arrow.sourcePort.contains("f0"), s"precondition: the arrow ${arrow.id} does not start at the cell f0")
        startDrag(canvas, arrow, ArrowEndpoint.source)
        val group = canvas.nodeGroup(c.source)
        check(group.isDefined, s"precondition: the canvas does not show the node ${c.source}")
        val r        = group.get.getBoundingClientRect()
        val (px, py) = (r.left + r.width * c.grip._1, r.top + r.height * c.grip._2)
        check(
          canvas.state.recordCells.cellPathAtClientPoint(c.source, px, py).contains(List(1)),
          s"precondition: the client point ($px, $py) is not over the cell f1 of ${c.source}"
        )
        canvas.svg.dispatchEvent(mouse("mousemove", px, py))
        afterMicrotasks:
          same(
            canvas.state.selection.now().ids.toSet[ElementId],
            Set[ElementId](arrow.id, c.source),
            s"the pointer is over the cell f1 of ${c.source}, but the canvas does not select ${c.source} as the new end"
          )
          canvas.svg.dispatchEvent(mouse("mouseup", px, py))
        .flatMap: _ =>
          afterMicrotasks:
            val fromA = canvas.state.fullGraphNow().arrows.values.filter(a => a.source == c.source && a.target == c.target)
            same(
              fromA.map(_.sourcePort).toList,
              List(Some("f1")),
              s"after the drop on the cell f1, the arrows ${c.source} -> ${c.target} do not start at f1: ${fromA.map(_.id)}"
            )

  // ---------------------------------------------------------------------------
  // An arrow to a collapsed box
  // ---------------------------------------------------------------------------

  // The group `x` is collapsed, and the canvas shows it as the box `x`. The
  // arrow `a -> c` shows as the arrow `x -> c`. That arrow is only in the
  // visible graph, not in the full graph. A selected box arrow shows its
  // disks. A move of its end then gets the message of `moveArrowEndpoint`.
  // Failure: a lookup in the full graph finds no arrow, and the canvas shows
  // no disk.
  test("a selected arrow to a collapsed box shows an endpoint disk at each end"):
    val group = GroupId("x")
    val box   = NodeId("x")
    val c     = NodeId("c")
    withCanvas("digraph { subgraph cluster_x { a } a -> c }", NodeId("a")): canvas =>
      val state = canvas.state
      state.selection.set2(group)
      state.selection.toggleCollapse()
      Waits
        .settle(canvas.nodeGroup(box).isDefined, timeoutMs = 10000)
        .flatMap: _ =>
          check(canvas.nodeGroup(box).isDefined, s"precondition: the canvas does not show the box $box")
          val boxArrow = state.visibleGraphNow().arrows.values.find(a => a.source == box && a.target == c)
          check(boxArrow.isDefined, s"precondition: the visible graph has no arrow $box -> $c: ${state.visibleGraphNow().arrows.keys}")
          check(
            !state.fullGraphNow().arrows.contains(boxArrow.get.id),
            s"precondition: the full graph has the box arrow ${boxArrow.get.id}"
          )
          state.selection.set2(boxArrow.get.id)
          waitForDisks(canvas).map: _ =>
            check(canvas.disk(ArrowEndpoint.source).isDefined, s"the selected box arrow ${boxArrow.get.id} has no source endpoint disk")
            check(canvas.disk(ArrowEndpoint.target).isDefined, s"the selected box arrow ${boxArrow.get.id} has no target endpoint disk")

end MoveArrowEndpointSpec
