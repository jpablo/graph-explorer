package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.eventbus.EventBus
import com.raquo.airstream.ownership.{ManualOwner, Owner}
import munit.FunSuite
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.graphexplorer.viewer.models.NodeId
import org.jpablo.graphexplorer.viewer.utils.TestHelpers

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** A person locks a diagram (CONTEXT.md, "Locked"). Then the app does not
  * change the source of the diagram: not from the canvas, not from the editor,
  * and not from a paste. A change of the metadata stays possible, because the
  * metadata is not in the source.
  *
  * `LockedFileSpec` has the cases of a file on the desktop: the save, the lock
  * from `gx`, and a change of the file by a different tool.
  */
class LockedDiagramSpec extends FunSuite with TestHelpers:

  // The specs share one Graphviz for the whole test run (see
  // `TestHelpers.sharedGraphviz`). Each test has one owner (see `RendererChoiceSpec`).

  override def munitFixtures = List(mockStorageFixture())

  private val source =
    """digraph G {
      |  a -> b
      |  b -> c
      |}""".stripMargin

  // A source in a different language, for the tests of the extractor.
  private val mermaid = "flowchart LR\n  A --> B\n"

  private def waitFor(description: => String, timeoutMs: Int = 10000)(condition: => Boolean): Future[Unit] =
    val p     = Promise[Unit]()
    val start = js.Date.now()
    def loop(): Unit =
      if condition then p.trySuccess(())
      else if js.Date.now() - start >= timeoutMs then
        p.tryFailure(new RuntimeException(s"Timed out after ${timeoutMs}ms. Expected: $description"))
      else js.timers.setTimeout(20)(loop())
    loop()
    p.future

  /** A viewer on `source`, after the parse, with the messages that it gives. */
  private final class Fixture(name: String, graphviz: Graphviz)(using Owner):
    private val errorBus = EventBus[String]()
    val errors           = mutable.Buffer.empty[String]
    errorBus.events.foreach(errors += _)
    val state  = ViewerState(ViewTarget.diagram(name), graphviz, initialSource = Some(source), errorBus = errorBus)
    val drawn  = state.svgWithPositions.observe
    def nodes  = state.fullGraphNow().nodeIds.toSet
    def parsed = waitFor(s"the parse of the source of $name")(nodes.size == 3)

  private def withViewer(name: String)(body: Fixture => Future[Unit]): Future[Unit] =
    val owner = ManualOwner()
    TestHelpers.sharedGraphviz
      .flatMap: graphviz =>
        val fixture = Fixture(name, graphviz)(using owner)
        fixture.parsed.flatMap(_ => body(fixture))
      .transform: outcome =>
        owner.killSubscriptions()
        outcome

  test("a canvas edit on a locked diagram changes nothing, and the person gets a message"):
    withViewer("locked-canvas"): f =>
      f.state.metadata.locked.set(true)
      f.state.selection.set2(NodeId("c"))
      f.state.addNodeWithSmartConnection()
      afterMicrotasks:
        assertEquals(f.nodes, Set(NodeId("a"), NodeId("b"), NodeId("c")))
        assertEquals(f.state.sourceText.now(), source)
        assertEquals(f.errors.toList, List(InternalPhases.lockedCanvasEdit))
        // The new node did not come, so the selection stays on `c`.
        assertEquals(f.state.selection.targets().ids, Set(NodeId("c")))

  test("an edit of the text of a locked diagram changes nothing, and the person gets a message"):
    withViewer("locked-text"): f =>
      f.state.metadata.locked.set(true)
      f.state.sourceText.set("digraph G {\n  x -> y\n}")
      afterMicrotasks:
        assertEquals(f.state.sourceText.now(), source)
        assertEquals(f.nodes, Set(NodeId("a"), NodeId("b"), NodeId("c")))
        assertEquals(f.errors.toList, List(InternalPhases.lockedTextEdit))

  test("a paste on a locked diagram changes nothing, and the person gets a message"):
    withViewer("locked-paste"): f =>
      f.state.metadata.locked.set(true)
      f.state.pasteDiagramFromGesture("digraph G {\n  x -> y\n}")
      afterMicrotasks:
        assertEquals(f.state.sourceText.now(), source)
        assert(f.errors.exists(_.contains("locked")), s"no message about the lock: ${f.errors}")

  test("a hide on a locked diagram applies, because it changes only the metadata"):
    withViewer("locked-hide"): f =>
      f.state.metadata.locked.set(true)
      f.state.hiddenElements.add(Set(NodeId("a")))
      waitFor("the visible graph does not have the node a")(!f.state.visibleGraphNow().nodeIds.toSet.contains(NodeId("a"))).map: _ =>
        assertEquals(f.state.sourceText.now(), source)
        assertEquals(f.errors.toList, Nil)

  test("the selection of a renderer and of an extractor applies on a locked diagram"):
    withViewer("locked-selections"): f =>
      f.state.metadata.locked.set(true)
      f.state.rendererOption.select("mermaid")
      f.state.setDiagramFormat(DiagramFormat.DOT)
      afterMicrotasks:
        assertEquals(f.state.metadata.now().renderer, Some("mermaid"))
        assertEquals(f.state.metadata.now().extractor, Some("dot"))
        assertEquals(f.state.sourceText.now(), source)
        assertEquals(f.errors.toList, Nil)

  test("after the unlock, a canvas edit applies"):
    withViewer("locked-then-unlocked"): f =>
      f.state.metadata.locked.set(true)
      f.state.metadata.locked.set(false)
      f.state.selection.set2(NodeId("c"))
      f.state.addNodeWithSmartConnection()
      waitFor("the new node is in the graph")(f.nodes.size == 4).map: _ =>
        assertEquals(f.errors.toList, Nil)

  test("the lock is in the stored diagram, and the next view of the diagram has it"):
    val owner = ManualOwner()
    TestHelpers.sharedGraphviz
      .flatMap: graphviz =>
        val first = Fixture("locked-stored", graphviz)(using owner)
        first.parsed
          .flatMap: _ =>
            first.state.metadata.locked.set(true)
            afterMicrotasks(())
          .flatMap: _ =>
            val second = ViewerState(ViewTarget.diagram("locked-stored"), graphviz)(using owner)
            afterMicrotasks(assertEquals(second.metadata.now().locked, true))
      .transform: outcome =>
        owner.killSubscriptions()
        outcome

  // Review finding. The restore of a diagram takes the language from the
  // stored extractor first. A paste or a push of a source in a different
  // language changed the language of the view, but not the extractor. Thus
  // the next view of the diagram parsed the Mermaid text as DOT.

  /** Select DOT, replace the source with Mermaid text, and open the diagram again. */
  private def nextViewAfter(name: String)(replace: ViewerState => Unit): Future[Unit] =
    val owner = ManualOwner()
    TestHelpers.sharedGraphviz
      .flatMap: graphviz =>
        val first = Fixture(name, graphviz)(using owner)
        first.parsed
          .flatMap: _ =>
            first.state.setDiagramFormat(DiagramFormat.DOT)
            replace(first.state)
            waitFor("the view shows the Mermaid text")(first.state.formatSelection.now() == DiagramFormat.Mermaid)
          .flatMap(_ => afterMicrotasks(()))
          .flatMap: _ =>
            val second = ViewerState(ViewTarget.diagram(name), graphviz)(using owner)
            afterMicrotasks:
              assert(second.sourceText.now().contains("A --> B"), s"precondition: the next view has the Mermaid text: ${second.sourceText.now()}")
              assertEquals(second.formatSelection.now(), DiagramFormat.Mermaid, "the next view has the language of the old extractor")
      .transform: outcome =>
        owner.killSubscriptions()
        outcome

  test("after a paste in a different language, the next view of the diagram has the language of the paste"):
    nextViewAfter("extractor-after-paste")(_.pasteDiagramFromGesture(mermaid))

  test("after a push of text in a different language, the next view of the diagram has the language of the text"):
    nextViewAfter("extractor-after-push")(_.replaceSourceDetectingFormat(mermaid))

  test("after an undo to a text in a different language, the next view of the diagram has the language of the text"):
    nextViewAfter("extractor-after-undo")(_.restoreSource(mermaid))

  test("a replace in the same language does not change the selected extractor"):
    withViewer("extractor-same-language"): f =>
      f.state.setDiagramFormat(DiagramFormat.DOT)
      val writes = mutable.Buffer.empty[Option[String]]
      f.state.metadata.signal.changes.foreach(m => writes += m.extractor)(using f.state.owner)
      f.state.replaceSourceDetectingFormat("digraph G {\n  x -> y\n}")
      afterMicrotasks:
        assertEquals(f.state.metadata.now().extractor, Some("dot"))
        assertEquals(writes.toList, Nil, "a replace in the same language wrote the metadata")

  test("a selected language sets the extractor, and Auto removes it"):
    withViewer("extractor-selection"): f =>
      val selected = f.state.formatOption.selected.observe(using f.state.owner)
      f.state.formatOption.select(DiagramFormat.DOT.toString)
      assertEquals(f.state.metadata.now().extractor, Some("dot"))
      assertEquals(f.state.metadata.now().autoDetectFormat, Some(false))
      assertEquals(selected.now(), DiagramFormat.DOT.toString)
      f.state.formatOption.select(f.state.formatOption.auto)
      assertEquals(f.state.metadata.now().extractor, None)
      assertEquals(f.state.metadata.now().autoDetectFormat, Some(true))
      assertEquals(selected.now(), f.state.formatOption.auto)
      Future.unit
