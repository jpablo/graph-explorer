package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.EventBus
import org.jpablo.graphexplorer.viewer.components.selection.NodeElement
import munit.FunSuite
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Color
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.{ArrowDirection, ArrowEndpointId, AttributeUpdates, ElementIds, GroupId, NodeId}
import org.jpablo.graphexplorer.viewer.state.mouseActions.MouseAction
import org.scalajs.dom
import org.jpablo.graphexplorer.viewer.utils.TestHelpers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Try

/** A collapsed group shows on the canvas as a box.
  *
  * The id of the box is a proxy node id: `CollapseOps.proxyIdFor` gives
  * `NodeId(g.value)`. A click on the box puts that id in the selection. The
  * graph has no node with that id, so each graph edit must resolve the id to
  * the group first. These tests collapse a group through the viewer API,
  * select the box as a click does, and then do one graph edit.
  */
class CollapsedBoxEditSpec extends FunSuite with TestHelpers:

  override def munitFixtures = List(mockStorageFixture())

  private val dot =
    """digraph G {
      |  subgraph cluster_x { label="X"; color="red"; a; b }
      |  c;
      |  d;
      |  a -> c;
      |  c -> d;
      |}""".stripMargin

  // `GroupId.fromDot` removes the `cluster_` prefix. Thus the group is `x`,
  // and the box on the canvas has the id `node:x`.
  private val group = GroupId("x")
  private val box   = NodeId("x")
  private val c     = NodeId("c")
  private val d     = NodeId("d")

  // A phantom node in the text has the id of the box, or the DOT name of the cluster.
  private val phantomIds: Set[NodeId] = Set(box, NodeId("cluster_x"))

  /** `assert`, with a message that reaches the log. In the Playwright runner a
    * failure can arrive with an empty message. The console does reach the log.
    */
  private def check(condition: Boolean, clue: => String): Unit =
    if !condition then println(s"[CollapsedBoxEditSpec] $clue")
    assert(condition, clue)

  private def collect(bus: EventBus[String], owner: ManualOwner): () => List[String] =
    var messages = List.empty[String]
    bus.events.foreach(message => messages = message :: messages)(using owner)
    () => messages

  /** The viewer, and the messages that it sent to the person. */
  private case class Run(state: ViewerState, messages: () => List[String])

  // Each viewer of a test uses its own owner. `afterEach` kills it, so a
  // viewer with a failed state does not stay alive into the next test.
  private var owners = List.empty[ManualOwner]

  override def afterEach(context: AfterEach): Unit =
    owners.foreach(_.killSubscriptions())
    owners = Nil

  /** Open `dot`, wait for the first parse, collapse the group, and click its box.
    * Then run `act`, wait for the microtasks, and give the result.
    */
  private def onSelectedBox[A](act: Run => A): Future[(Run, A)] =
    val owner = ManualOwner()
    owners = owner :: owners
    // `withGraphvizAsync` gives a `Future[Unit]`, and this helper gives a value.
    Graphviz.build().flatMap: graphviz =>
      val errors = EventBus[String]()
      val infos  = EventBus[String]()
      val errorMessages = collect(errors, owner)
      val infoMessages  = collect(infos, owner)
      val state =
        ViewerState(ViewTarget.diagram("collapsed-box"), graphviz, errorBus = errors, infoBus = infos, initialSource = Some(dot))(
          using owner
        )
      val run = Run(state, () => errorMessages() ++ infoMessages())
      afterMicrotasks:
        val groups = state.fullGraphNow().groupIds
        check(groups.contains(group), s"precondition: the graph has $group, but has $groups")
        // The canvas control of a group does this (`onCollapseGroup` in ViewerState).
        state.selection.set2(group)
        state.selection.toggleCollapse()
        check(state.renderedId(group) == box, s"precondition: the canvas shows $group as $box, not as ${state.renderedId(group)}")
        // A click on the box: `SelectableElementStrategy` gives `NodeId(title.textContent)`.
        state.selection.updateSelectionStatus(box)(shiftKey = false)
        val selected = state.selection.now()
        check(selected.size == 1 && selected.contains(box), s"precondition: the box is selected: $selected")
        act(run)
      .flatMap(result => afterMicrotasks(run -> result))

  /** The arrow ends in `g` that name no node of `g`. */
  private def danglingEnds(g: ViewerGraph): Set[NodeId] =
    g.arrows.values.flatMap(a => Seq(a.source, a.target)).toSet -- g.nodeIds

  /** Parse the current text again, as a reload or an expand does, and find the phantom node. */
  private def checkNoPhantomInText(state: ViewerState): Future[Unit] =
    val text = state.sourceText.now()
    state.languages
      .forFormat(DiagramFormat.DOT)
      .textToGraph(text)
      .map: reparsed =>
        val phantomNodes = reparsed.nodeIds intersect phantomIds
        check(phantomNodes.isEmpty, s"the text has the phantom node(s) $phantomNodes:\n$text")
        val phantomArrows =
          reparsed.arrows.values.filter(a => phantomIds(a.source) || phantomIds(a.target)).map(a => s"${a.source} -> ${a.target}")
        check(phantomArrows.isEmpty, s"the text has an arrow to or from the box: $phantomArrows:\n$text")

  // ------------------------------------------------------------------ (a)

  test("group a collapsed box: the edit does not fail the state, and the text gets no phantom node"):
    // Bug: `createGroupWithLabel` gives the box id to `moveToNewGroup`. The new
    // membership names a node that the graph does not have. The serializer
    // then throws NoSuchElementException, and the root state Var becomes a
    // Failure. Each later edit then fails with "Unable to update a failed Var".
    onSelectedBox: run =>
      val state = run.state
      // The key "g" does this. The dialog then calls `createGroupWithLabel`.
      state.selection.group()
      val pending = state.pendingNewGroupV.now().map(_.elementIds)
      check(pending.isDefined, "precondition: the group command asks for a label")
      Try(state.createGroupWithLabel(pending.get, "Outer")).failed.toOption
    .flatMap: (run, thrown) =>
      val state = run.state
      check(thrown.isEmpty, s"the group edit threw: ${thrown.map(e => s"${e.getClass.getName}: ${e.getMessage}")}")
      val graph = state.phases.fullGraphV.tryNow()
      check(graph.isSuccess, s"the graph Var is failed: ${graph.failed.map(_.getMessage)}")
      // A later edit must still apply.
      state.updateLabel(c, "Later")
      val text = Try(state.sourceText.now())
      check(text.toOption.exists(_.contains("Later")), s"a later edit did not apply: $text")
      checkNoPhantomInText(state)

  // ------------------------------------------------------------------ (b)

  test("add a node with a connection from a collapsed box: the text gets no arrow from a phantom node"):
    // Bug: `addNodeWithSmartConnection` gives `Some(box id)` to the graph op.
    // The graph op adds an arrow from `x` to the new node. The text then has
    // the node `x`, which becomes a real node on an expand or a reload.
    onSelectedBox: run =>
      run.state.addNodeWithSmartConnection()
    .flatMap: (run, _) =>
      checkNoPhantomInText(run.state).map: _ =>
        val graph = run.state.fullGraphNow()
        check(danglingEnds(graph).isEmpty, s"an arrow ends in a node that the graph does not have: ${danglingEnds(graph)}")

  // ------------------------------------------------------------------ (c)

  test("a new arrow to a collapsed box: the text gets no phantom node"):
    // Bug: a new-arrow drop on the box calls `addArrow(c, box id)`
    // (AddNewArrowOps.handleAddNewArrowMouseUp). The arrow end is the box id,
    // so the text gets the arrow `c -> x` and the phantom node `x`.
    onSelectedBox: run =>
      val state = run.state
      // The drag selects the start node and the node under the pointer (`selectWithClosestNode`).
      state.selection.set2(c, box)
      state.addArrow(c, box)
    .flatMap: (run, _) =>
      checkNoPhantomInText(run.state).map: _ =>
        val graph = run.state.fullGraphNow()
        check(danglingEnds(graph).isEmpty, s"an arrow ends in a node that the graph does not have: ${danglingEnds(graph)}")

  // ------------------------------------------------------------------ (d)

  test("a toolbar edit of a collapsed box keeps the box selected"):
    // Bug: after each graph change, the viewer removes from the selection each
    // id that the FULL graph does not have. The full graph has the group, not
    // the box, so the box leaves the selection after its own edit.
    onSelectedBox: run =>
      val state = run.state
      // The toolbar of the selected nodes does this (AttributesToolbar.selectionView).
      state.elementAttributesUpdates(state.selection.now()).set(AttributeUpdates.of(Color -> "blue"))
    .flatMap: (run, _) =>
      val state = run.state
      val color = state.fullGraphNow().groups.get(group).flatMap(_.attributes.get(Color)).map(_.toString)
      check(color.contains("blue"), s"precondition: the edit applies to the group, but its color is $color")
      check(state.selection.now().contains(box), s"the box left the selection after the edit: ${state.selection.now()}")
      Future.unit

  // ------------------------------------------------------------------ (e)

  test("reset attributes on a collapsed box resets the group, or the viewer tells the person"):
    // Bug: `resetAttributes` gives the box id to `AttributesOps`. The graph has
    // no node with that id, so the op finds no attributes and does nothing.
    // The person gets no reset and no message.
    onSelectedBox: run =>
      val state  = run.state
      val before = state.fullGraphNow().groups.get(group).flatMap(_.attributes.get(Color)).map(_.toString)
      check(before.contains("red"), s"precondition: the group has the color red, but has $before")
      state.selection.resetAttributes()
    .flatMap: (run, _) =>
      val color    = run.state.fullGraphNow().groups.get(group).flatMap(_.attributes.get(Color)).map(_.toString)
      val messages = run.messages()
      check(
        color.isEmpty || messages.nonEmpty,
        s"reset attributes on the box did nothing: the group color is still $color, and no message came ($messages)"
      )
      Future.unit

  // ------------------------------------------------- the box is the group

  test("group a collapsed box: the new group holds the collapsed group, and the new group is selected"):
    // The same action on the expanded group puts that group into the new group.
    onSelectedBox: run =>
      val state = run.state
      state.selection.group()
      state.pendingNewGroupV.now().foreach(pending => state.createGroupWithLabel(pending.elementIds, "Outer"))
    .flatMap: (run, _) =>
      val state  = run.state
      val graph  = state.fullGraphNow()
      val parent = graph.membership(group)
      val label  = parent.flatMap(graph.groups.get).map(_.label.toString)
      check(label.contains("Outer"), s"the collapsed group is not in the new group: parent $parent, label $label")
      check(state.selection.now().ids.toSet == parent.toSet, s"the new group is not selected: ${state.selection.now()}")
      check(state.renderedId(group) == box, "the group is no longer collapsed")
      Future.unit

  test("add a node from a collapsed box: the node goes into the collapsed group, and the box stays selected"):
    // The same action on the expanded group adds the node to that group.
    // A node in a collapsed group has no shape on the canvas, so the box stays selected.
    onSelectedBox: run =>
      val before = run.state.fullGraphNow()
      run.state.addNodeWithSmartConnection()
      before
    .flatMap: (run, before) =>
      val after = run.state.fullGraphNow()
      val added = after.nodeIds -- before.nodeIds
      check(added.size == 1, s"one node must be added, not $added")
      check(added.forall(n => after.membership(n).contains(group)), s"the new node is not in $group: ${added.map(after.membership)}")
      check(after.arrowIds == before.arrowIds, s"the edit added an arrow: ${after.arrowIds -- before.arrowIds}")
      val selected = run.state.selection.now()
      check(selected.size == 1 && selected.contains(box), s"the box is not selected: $selected")
      Future.unit

  test("duplicate a collapsed box: the copy is a copy of the expanded group"):
    onSelectedBox: run =>
      val before = run.state.fullGraphNow()
      run.state.selection.duplicateSelection()
      before
    .flatMap: (run, before) =>
      val after     = run.state.fullGraphNow()
      val newGroups = after.groupIds -- before.groupIds
      val newNodes  = after.nodeIds -- before.nodeIds
      check(newGroups.size == 1, s"the duplicate did not copy the group: $newGroups")
      check(newNodes.size == 2 && newNodes.forall(n => newGroups.toSeq.map(Some(_)).contains(after.membership(n))),
        s"the copy of the group does not hold copies of a and b: $newNodes")
      Future.unit

  // ------------------------------------------------- an arrow cannot end on a box

  test("a new arrow to or from a collapsed box is refused with a message, and the text does not change"):
    // An arrow cannot end on a group, so the viewer must not connect a member in place of the box.
    onSelectedBox: run =>
      val state  = run.state
      val before = state.sourceText.now()
      state.selection.set2(c, box)
      state.addArrow(c, box)
      state.selection.set2(box, c)
      state.addArrow(box, c)
      before
    .flatMap: (run, before) =>
      val messages = run.messages()
      check(run.state.sourceText.now() == before, s"the refused arrow changed the text:\n${run.state.sourceText.now()}")
      check(
        messages.size == 2 && messages.forall(m => m.contains("collapsed group") && m.contains("\"X\"")),
        s"each refused arrow must send one message that names the group: $messages"
      )
      Future.unit

  test("a moved arrow end on a collapsed box is refused with a message, and the text does not change"):
    onSelectedBox: run =>
      val state  = run.state
      val before = state.sourceText.now()
      val cToD   = state.fullGraphNow().arrows.values.find(a => a.source == c && a.target == d).map(_.id)
      check(cToD.isDefined, "precondition: the graph has the arrow c -> d")
      state.moveArrowEndpoint(cToD.get, ArrowEndpointId.TargetId(box))
      before
    .flatMap: (run, before) =>
      val messages = run.messages()
      check(run.state.sourceText.now() == before, s"the refused move changed the text:\n${run.state.sourceText.now()}")
      check(messages.exists(_.contains("collapsed group")), s"the refused move sent no message: $messages")
      Future.unit

  test("a move of the end of an arrow to a collapsed box is refused, and the state does not fail"):
    // Bug: the arrow `x -> c` is in the visible graph only. `ViewerGraph.moveArrowEndpoint`
    // reads `arrows(arrowId)`, which throws, and then the root state Var is a Failure.
    onSelectedBox: run =>
      val state    = run.state
      val before   = state.sourceText.now()
      val boxArrow = (state.visibleGraphNow().arrowIds -- state.fullGraphNow().arrowIds).headOption
      check(boxArrow.isDefined, s"precondition: the canvas has an arrow to the box: ${state.visibleGraphNow().arrowIds}")
      state.moveArrowEndpoint(boxArrow.get, ArrowEndpointId.TargetId(d))
      before
    .flatMap: (run, before) =>
      val graph = run.state.phases.fullGraphV.tryNow()
      check(graph.isSuccess, s"the graph Var is failed: ${graph.failed.map(_.getMessage)}")
      check(run.state.sourceText.now() == before, s"the refused move changed the text:\n${run.state.sourceText.now()}")
      check(run.messages().exists(_.contains("collapsed group")), s"the refused move sent no message: ${run.messages()}")
      Future.unit

  test("a collapsed box has no new-arrow control, and a node has one"):
    // An expanded group has no new-arrow control. The control of a box made an arrow to the box.
    onSelectedBox: run =>
      val state  = run.state
      val svgNs  = "http://www.w3.org/2000/svg"
      val svg    = dom.document.createElementNS(svgNs, "svg")
      val parent = dom.document.createElementNS(svgNs, "g").asInstanceOf[dom.svg.G]
      svg.appendChild(parent)
      dom.document.body.appendChild(svg)
      def nodeElement(id: NodeId): NodeElement =
        val g     = dom.document.createElementNS(svgNs, "g").asInstanceOf[dom.svg.G]
        val title = dom.document.createElementNS(svgNs, "title")
        g.setAttribute("class", "node")
        title.textContent = id.value
        g.appendChild(title)
        parent.appendChild(g)
        NodeElement(g, state.selectionStrategyNow())
      try
        val onBox  = state.buildNewArrowControl(parent, nodeElement(box), MouseAction.Inactive, ArrowDirection.forward)
        val onNode = state.buildNewArrowControl(parent, nodeElement(c), MouseAction.Inactive, ArrowDirection.forward)
        (onBox.isDefined, onNode.isDefined)
      finally svg.remove()
    .flatMap: (_, controls) =>
      check(controls._2, "control: a node has no new-arrow control")
      check(!controls._1, "the box has a new-arrow control")
      Future.unit

  // ------------------------------------------------- the prune

  test("after a graph change, the selection keeps the box and drops an id that neither graph has"):
    onSelectedBox: run =>
      val state = run.state
      state.selection.set2(box, c)
      // A graph change that removes c, with no change of the selection.
      state.phases.fullGraphV.update(_.removeElements(ElementIds.from(c)))
    .flatMap: (run, _) =>
      val selected = run.state.selection.now()
      check(!run.state.fullGraphNow().nodeIds.contains(c), "precondition: the edit removed c")
      check(selected.size == 1 && selected.contains(box), s"the selection must be the box alone: $selected")
      Future.unit

end CollapsedBoxEditSpec
