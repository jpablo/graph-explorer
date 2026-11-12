package org.jpablo.graphexplorer.viewer.backends.mermaid

import com.raquo.laminar.api.L.unsafeWindowOwner
import munit.FunSuite
import org.jpablo.graphexplorer.viewer.components.selection.{EdgeElement, NodeElement, SelectableElement}
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Label
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.{Attributes, NodeId}
import org.jpablo.graphexplorer.viewer.state.{DiagramLoadStatus, ViewTarget, ViewerState}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** A Mermaid state diagram is an editable diagram, like a flowchart.
  *
  * Before, the Mermaid backend modeled flowcharts only. A state diagram was
  * "render-only": the canvas drew it, but the graph stayed empty and out of
  * sync. Thus "New node" opened its dialog, and then the edit was ignored with
  * no message. These tests use the real Mermaid parser and renderer.
  *
  * The checks throw AssertionError, not munit's `assert`: through the Scala.js
  * test bridge and the sbt client, a munit FailException arrives with no message.
  */
class StateDiagramEditingSpec extends FunSuite with TestHelpers:

  given com.raquo.airstream.ownership.Owner = unsafeWindowOwner

  override def munitFixtures = List(mockStorageFixture())

  /** The diagram of the bug report. */
  private val Editor =
    """stateDiagram-v2
      |  [*] --> Idle
      |  Idle --> Selecting : click
      |  Selecting --> Editing : Enter
      |  Editing --> Selecting : save
      |  Selecting --> Idle : Escape
      |  Selecting --> Selecting : s / shift+S
      |  Editing --> [*] : close
      |""".stripMargin

  /** A composite state, with its own start state inside. */
  private val Composite =
    """stateDiagram-v2
      |  [*] --> Active
      |  state Active {
      |    [*] --> Typing
      |    Typing --> Paused : pause
      |    Paused --> Typing : resume
      |  }
      |  Active --> Done : quit
      |  Done --> [*]
      |""".stripMargin

  // ---------------------------------------------------------------- helpers

  private def check(condition: Boolean, message: => String): Unit =
    if !condition then throw AssertionError(message)

  private def waitFor(condition: => Boolean, description: => String, timeoutMs: Int = 10000): Future[Unit] =
    val p     = Promise[Unit]()
    val start = js.Date.now()
    def loop(): Unit =
      if condition then p.trySuccess(())
      else if js.Date.now() - start >= timeoutMs then
        p.tryFailure(AssertionError(s"Timed out after ${timeoutMs}ms waiting for $description"))
      else js.timers.setTimeout(20)(loop())
    loop()
    p.future

  private def parse(text: String): Future[ViewerGraph] =
    MermaidBackend().textToGraph(text).recover { case e =>
      throw AssertionError(s"the state diagram did not parse into a graph: ${e.getMessage}\n$text")
    }

  private def labelOf(graph: ViewerGraph, id: NodeId): String =
    graph.nodes.get(id).flatMap(_.attributes.get(Label.attrId)).map(_.toString).filter(_.nonEmpty).getOrElse(id.value)

  /** Each arrow as (source, target, label). An endpoint is its label, or its id
    * when it has no label. Thus the check does not depend on the ids that a
    * writer gives to new states.
    */
  private def transitions(graph: ViewerGraph): Set[(String, String, String)] =
    graph.arrows.values.map: arrow =>
      (
        labelOf(graph, arrow.source),
        labelOf(graph, arrow.target),
        arrow.attributes.get(Label.attrId).map(_.toString).getOrElse("")
      )
    .toSet

  private val NamedTransitions = Set(
    ("Idle", "Selecting", "click"),
    ("Selecting", "Editing", "Enter"),
    ("Editing", "Selecting", "save"),
    ("Selecting", "Idle", "Escape"),
    ("Selecting", "Selecting", "s / shift+S")
  )

  private def firstLine(text: String): String =
    text.linesIterator.map(_.trim).find(l => l.nonEmpty && !l.startsWith("%%")).getOrElse("")

  /** A viewer of `source` whose graph is in sync with its text. */
  private def readyViewerF(name: String, source: String): Future[ViewerState] =
    // withGraphvizAsync returns Future[Unit]; keep the state through a promise.
    val p = Promise[ViewerState]()
    withGraphvizAsync { graphviz =>
      val state = ViewerState(ViewTarget.diagram(name), graphviz, initialSource = Some(source))
      waitFor(state.loadStatus.now() != DiagramLoadStatus.Loading, s"the parse of $name").map: _ =>
        if state.loadStatus.now() == DiagramLoadStatus.Ready then p.trySuccess(state)
        else p.tryFailure(AssertionError(s"the state diagram must be editable, but the load status is ${state.loadStatus.now()}"))
        ()
    }.failed.foreach(p.tryFailure)
    p.future

  // ------------------------------------------------------------------ tests

  test("a state diagram parses into its states and transitions"):
    parse(Editor).map: graph =>
      val names = graph.nodeIds.map(labelOf(graph, _))
      Seq("Idle", "Selecting", "Editing").foreach: state =>
        check(names.contains(state), s"no node for the state $state in $names")
      val found = transitions(graph)
      check(NamedTransitions.subsetOf(found), s"missing transitions: ${NamedTransitions -- found}; found $found")
      // [*] --> Idle and Editing --> [*] : close are transitions too.
      check(graph.arrows.size == 7, s"expected 7 transitions, found ${graph.arrows.size}: $found")
      check(found.exists((_, to, label) => to == "Idle" && label.isEmpty), s"no start transition into Idle: $found")
      check(found.exists((from, _, label) => from == "Editing" && label == "close"), s"no end transition from Editing: $found")

  test("a state diagram is not view-only"):
    readyViewerF("state-not-view-only", Editor).map: state =>
      check(state.editorNotice.now().isEmpty, s"unexpected notice: ${state.editorNotice.now()}")

  test("a new node on a state diagram lands in its source (the reported bug)"):
    for
      state <- readyViewerF("state-add-node", Editor)
      _ = state.addNodeWithSmartConnection(Attributes.of(Label -> "Saving"))
      _ <- waitFor(state.sourceText.now() != Editor, "the source to change after New node")
      text = state.sourceText.now()
      _ = check(firstLine(text).startsWith("stateDiagram"), s"the source is no longer a state diagram:\n$text")
      graph <- parse(text)
    yield
      val names = graph.nodeIds.map(labelOf(graph, _))
      check(names.contains("Saving"), s"no state Saving after New node; states $names; source:\n$text")
      val found = transitions(graph)
      check(NamedTransitions.subsetOf(found), s"the edit lost transitions: ${NamedTransitions -- found}; source:\n$text")
      check(graph.arrows.size == 7, s"a standalone new state adds no transition; found ${graph.arrows.size}:\n$text")

  test("a new node connects to the selected state"):
    for
      state <- readyViewerF("state-add-connected", Editor)
      _ = state.selection.set2(NodeId("Editing"))
      _ = state.addNodeWithSmartConnection(Attributes.of(Label -> "Saving"))
      _ <- waitFor(state.sourceText.now() != Editor, "the source to change after New node")
      text = state.sourceText.now()
      graph <- parse(text)
    yield
      val found = transitions(graph)
      check(found.exists((from, to, _) => from == "Editing" && to == "Saving"), s"no transition Editing -> Saving:\n$text")
      check(NamedTransitions.subsetOf(found), s"the edit lost transitions: ${NamedTransitions -- found}; source:\n$text")

  test("each drawn state and transition is an element of the graph, and each element is drawn"):
    val backend = MermaidBackend()
    for
      graph    <- parse(Editor)
      rendered <- backend.textToSvg(Editor)
    yield
      val drawn = SelectableElement.findAll(rendered.svg.ref, backend.selectionStrategy)
      val drawnNodes  = drawn.collect { case n: NodeElement => n.elementId }.toSet
      val drawnArrows = drawn.collect { case e: EdgeElement => e.elementId }.toSet
      check(drawnNodes == graph.nodeIds.toSet, s"drawn states $drawnNodes, graph states ${graph.nodeIds.toSet}")
      check(drawnArrows == graph.arrowIds.toSet, s"drawn transitions $drawnArrows, graph transitions ${graph.arrowIds.toSet}")

  test("a composite state is a group, and an edit keeps it"):
    for
      before <- parse(Composite)
      _ = check(before.groupIds.nonEmpty, s"no group for the composite state Active: ${before.nodeIds}")
      inner = before.memberships.collect { case (member: NodeId, _) => labelOf(before, member) }.toSet
      _ = check(Set("Typing", "Paused").subsetOf(inner), s"Typing and Paused must be in the group Active; members $inner")
      state <- readyViewerF("state-composite", Composite)
      _ = state.addNodeWithSmartConnection(Attributes.of(Label -> "Saving"))
      _ <- waitFor(state.sourceText.now() != Composite, "the source to change after New node")
      text = state.sourceText.now()
      after <- parse(text)
    yield
      check(before.nodeIds.toSet.subsetOf(after.nodeIds.toSet), s"the edit lost states:\n$text")
      check(before.groupIds.toSet == after.groupIds.toSet, s"the edit changed the groups:\n$text")
      check(before.memberships.toSet.subsetOf(after.memberships.toSet), s"the edit changed the members of Active:\n$text")
      check(transitions(before).subsetOf(transitions(after)), s"the edit lost transitions:\n$text")
