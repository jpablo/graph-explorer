package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.ownership.{ManualOwner, Owner}
import munit.FunSuite
import org.jpablo.graphexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.graphexplorer.viewer.components.selection.{ClusterElement, EdgeElement, NodeElement, SelectableElement}
import org.jpablo.graphexplorer.viewer.graph.ViewerGraphElements
import org.jpablo.graphexplorer.viewer.models.{ArrowId, GroupId, NodeId}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** A person selects the renderer of a diagram apart from the language of its
  * source (ADR 0002).
  *
  * These tests use the real Graphviz and the real Mermaid in the browser. Each
  * test reads the drawing as the canvas does: with the selection strategy of
  * the renderer that drew it. Thus a test fails when an element of the graph
  * is drawn but the canvas cannot select it.
  */
class RendererChoiceSpec extends FunSuite with TestHelpers:

  /** One Graphviz for the spec, and one owner for each test.
    *
    * The end of a test ends each subscription of its viewers. A viewer that
    * stays alive keeps its Graphviz instance and its drawings, and the specs
    * that run after this one then time out.
    */
  private lazy val sharedGraphviz: Future[Graphviz] = TestHelpers.sharedGraphviz

  private def withViewers(body: Owner ?=> Graphviz => Future[Unit]): Future[Unit] =
    val owner = ManualOwner()
    sharedGraphviz
      .flatMap(graphviz => body(using owner)(graphviz))
      .transform: outcome =>
        owner.killSubscriptions()
        outcome

  override def munitFixtures = List(mockStorageFixture())

  private val dotSource =
    """digraph G {
      |  a -> b
      |  b -> c
      |}""".stripMargin

  private val mermaidSource =
    """flowchart LR
      |  A[Start] --> B[End]
      |  B --> C
      |""".stripMargin

  private def waitFor(description: => String, timeoutMs: Int = 15000)(condition: => Boolean): Future[Unit] =
    val p     = Promise[Unit]()
    val start = js.Date.now()
    def loop(): Unit =
      if condition then p.trySuccess(())
      else if js.Date.now() - start >= timeoutMs then
        p.tryFailure(new RuntimeException(s"Timed out after ${timeoutMs}ms. Expected: $description"))
      else js.timers.setTimeout(20)(loop())
    loop()
    p.future

  /** A viewer on `source`, and a handle on its drawing. */
  private final class Fixture(name: String, source: String, graphviz: Graphviz)(using Owner):
    val state    = ViewerState(ViewTarget.diagram(name), graphviz, initialSource = Some(source))
    val drawing  = state.svgWithPositions.observe
    val renderer = state.currentRenderer.observe

    def svgText: String = drawing.now().fold("")(_.svg.ref.outerHTML)

    private def drawn: Seq[SelectableElement] =
      drawing.now().toSeq.flatMap(d => SelectableElement.findAll(d.svg.ref, state.selectionStrategyNow()))

    def drawnNodes: Set[NodeId]   = drawn.collect { case n: NodeElement => n.elementId }.toSet
    def drawnArrows: Set[ArrowId] = drawn.collect { case e: EdgeElement => e.elementId }.toSet
    def drawnGroups: Set[GroupId] = drawn.collect { case c: ClusterElement => c.elementId }.toSet

    def graphNodes: Set[NodeId]   = state.fullGraphNow().nodeIds.toSet
    def graphArrows: Set[ArrowId] = state.fullGraphNow().arrowIds.toSet

    def parsed: Future[Unit] = waitFor(s"the parse of the source of $name")(state.fullGraphNow().nodes.nonEmpty)

    def drawsTheGraph(with_ : String): Future[Unit] =
      waitFor(
        s"$with_ draws the nodes $graphNodes and the arrows $graphArrows. " +
          s"Drawn by ${renderer.now().id}: $drawnNodes, $drawnArrows"
      )(
        renderer.now().id == with_ && drawnNodes == graphNodes && drawnArrows == graphArrows
      )

  // The marks of each drawing. Graphviz puts the drawing in `g.graph`. Mermaid
  // writes the kind of diagram in `aria-roledescription`.
  private def isGraphviz(svg: String) = svg.contains("class=\"graph\"")
  private def isMermaid(svg: String)  = svg.contains("aria-roledescription=\"flowchart")

  test("with no selection, the renderer of the language draws the source"):
    withViewers: graphviz =>
      val dot     = Fixture("choice-default-dot", dotSource, graphviz)
      val mermaid = Fixture("choice-default-mermaid", mermaidSource, graphviz)
      for
        _ <- dot.parsed
        _ <- mermaid.parsed
        _ <- dot.drawsTheGraph("graphviz")
        _ <- mermaid.drawsTheGraph("mermaid")
      yield
        assert(isGraphviz(dot.svgText), dot.svgText.take(300))
        assert(isMermaid(mermaid.svgText), mermaid.svgText.take(300))

  test("Mermaid draws a DOT source, and the canvas can select each node and each arrow"):
    withViewers: graphviz =>
      val f = Fixture("choice-dot-with-mermaid", dotSource, graphviz)
      for
        _ <- f.parsed
        _ = f.state.rendererOption.select("mermaid")
        _ <- f.drawsTheGraph("mermaid")
      yield
        assert(isMermaid(f.svgText), f.svgText.take(300))
        // The renderer does not change the source.
        assertEquals(f.state.sourceText.now(), dotSource)
        assertEquals(f.graphNodes, Set(NodeId("a"), NodeId("b"), NodeId("c")))

  test("Graphviz draws a Mermaid source, and the canvas can select each node and each arrow"):
    withViewers: graphviz =>
      val f = Fixture("choice-mermaid-with-graphviz", mermaidSource, graphviz)
      for
        _ <- f.parsed
        _ = f.state.rendererOption.select("graphviz")
        _ <- f.drawsTheGraph("graphviz")
      yield
        assert(isGraphviz(f.svgText), f.svgText.take(300))
        assertEquals(f.state.sourceText.now(), mermaidSource)
        // Graphviz draws the label of the Mermaid node, not its id.
        assert(f.svgText.contains("Start"), f.svgText.take(600))

  test("Graphviz draws a Mermaid subgraph as a group that the canvas can select"):
    withViewers: graphviz =>
      val source =
        """flowchart LR
          |  subgraph S [Services]
          |    A --> B
          |  end
          |  B --> C
          |""".stripMargin
      val f = Fixture("choice-mermaid-subgraph", source, graphviz)
      for
        _ <- f.parsed
        _ = f.state.rendererOption.select("graphviz")
        _ <- f.drawsTheGraph("graphviz")
      yield assertEquals(f.drawnGroups, Set(GroupId("S")))

  test("Mermaid draws a DOT source whose ids are not Mermaid ids, and the canvas can select each element"):
    withViewers: graphviz =>
      // Mermaid cannot have a dot or a space in an id, and `end` is a keyword.
      val source =
        """digraph G {
          |  subgraph "cluster my.svc" {
          |    "com.foo.Bar"
          |  }
          |  "com.foo.Bar" -> "my node"
          |  "my node" -> end
          |  "my node" -> end
          |}""".stripMargin
      val f = Fixture("choice-dot-ids", source, graphviz)
      for
        _ <- f.parsed
        _ = f.state.rendererOption.select("mermaid")
        _ <- f.drawsTheGraph("mermaid")
      yield
        assertEquals(f.graphNodes, Set(NodeId("com.foo.Bar"), NodeId("my node"), NodeId("end")))
        assertEquals(f.graphArrows.size, 3)
        val groups = f.state.fullGraphNow().groupIds.toSet - GroupId(ViewerGraphElements.defaultRootId.value)
        assert(groups.size == 1 && f.drawnGroups == groups, s"drawn groups ${f.drawnGroups}, graph groups $groups")

  test("a canvas edit with a different renderer writes the language of the source"):
    withViewers: graphviz =>
      val f = Fixture("choice-edit", dotSource, graphviz)
      for
        _ <- f.parsed
        _ = f.state.rendererOption.select("mermaid")
        _ <- f.drawsTheGraph("mermaid")
        _ = f.state.selection.set2(NodeId("c"))
        _ = f.state.addNodeWithSmartConnection()
        _ <- waitFor("the new node is in the graph")(f.graphNodes.size == 4)
        _ <- f.drawsTheGraph("mermaid")
      yield
        val text = f.state.sourceText.now()
        assert(text.trim.startsWith("digraph"), s"the edit did not write DOT:\n$text")
        assert(!text.contains("flowchart"), s"the edit wrote Mermaid text:\n$text")

  test("a kind of diagram with no graph keeps the renderer of its language"):
    withViewers: graphviz =>
      val source =
        """sequenceDiagram
          |  Browser->>API: GET /diagrams
          |  API-->>Browser: 200 OK
          |""".stripMargin
      val f = Fixture("choice-sequence", source, graphviz)
      f.state.rendererOption.select("graphviz")
      for
        _ <- waitFor("the load status says render-only")(f.state.loadStatus.now() match
          case DiagramLoadStatus.RenderOnly(_) => true
          case _                               => false)
        _ <- waitFor("Mermaid draws the sequence diagram")(
          f.renderer.now().id == "mermaid" && f.svgText.contains("aria-roledescription=\"sequence\"")
        )
      yield
        // The selection stays in the metadata, for a later source that has a graph.
        assertEquals(f.state.metadata.renderer.now(), Some("graphviz"))

  // Review finding. `currentRenderer` read the load status, and each parse
  // sets the status to Loading first. Thus a kind of diagram with no graph and
  // a selected renderer changed to that renderer and back at each parse. The
  // canvas showed an empty Graphviz drawing, then nothing, then the diagram.
  test("a kind of diagram with no graph keeps the renderer of its language during a parse"):
    withViewers: graphviz =>
      val source =
        """sequenceDiagram
          |  Browser->>API: GET /diagrams
          |""".stripMargin
      val f    = Fixture("choice-sequence-parse", source, graphviz)
      val seen = scala.collection.mutable.Buffer.empty[String]
      f.state.rendererOption.select("graphviz")
      for
        _ <- waitFor("Mermaid draws the sequence diagram")(
          f.renderer.now().id == "mermaid" && f.svgText.contains("aria-roledescription=\"sequence\"")
        )
        _ = f.state.currentRenderer.changes.foreach(renderer => seen += renderer.id)
        // An edit of the text, as a person types it.
        _ = f.state.sourceText.set(source + "  API-->>Browser: 200 OK\n")
        _ <- waitFor("Mermaid draws the new text")(f.svgText.contains("200 OK"))
      yield assert(!seen.contains("graphviz"), s"the renderer changed during the parse: ${seen.toList}")

  // Review finding. An arrow that Mermaid draws from the source has no stamp.
  // The strategy split its DOM id `L_A_my_node_0` at the last `_` that
  // matches. Thus the arrow to `my_node` got the id `A_my->node`, and a click
  // on it selected nothing.
  test("Mermaid draws a source with an underscore in a node id, and the canvas can select each arrow"):
    withViewers: graphviz =>
      val f = Fixture("choice-underscore", "flowchart LR\n  A --> my_node\n  my_node --> B_c\n", graphviz)
      f.parsed.flatMap(_ => f.drawsTheGraph("mermaid"))

  test("an id that this build does not have counts as no selection, and it stays in the metadata"):
    withViewers: graphviz =>
      val f        = Fixture("choice-unknown", dotSource, graphviz)
      val selected = f.state.rendererOption.selected.observe
      for
        _ <- f.parsed
        _ = f.state.metadata.renderer.set(Some("a-renderer-of-a-later-build"))
        _ <- f.drawsTheGraph("graphviz")
      yield
        assertEquals(selected.now(), f.state.rendererOption.auto)
        assertEquals(f.state.metadata.renderer.now(), Some("a-renderer-of-a-later-build"))

  test("Auto removes the selection"):
    withViewers: graphviz =>
      val f = Fixture("choice-auto", dotSource, graphviz)
      for
        _ <- f.parsed
        _ = f.state.rendererOption.select("mermaid")
        _ <- f.drawsTheGraph("mermaid")
        _ = f.state.rendererOption.select(f.state.rendererOption.auto)
        _ <- f.drawsTheGraph("graphviz")
      yield assertEquals(f.state.metadata.renderer.now(), None)

  test("Mermaid draws a Mermaid source with three arrows between two nodes, and the canvas can select each arrow"):
    // This case has no relation to the selection of a renderer. Before, the
    // second arrow and the third arrow had an id in the SVG that the graph
    // does not have, so a click on them selected nothing.
    withViewers: graphviz =>
      val f = Fixture("choice-parallel", "flowchart LR\n  A --> B\n  A --> B\n  A --> B\n", graphviz)
      for
        _ <- f.parsed
        _ <- f.drawsTheGraph("mermaid")
      yield assertEquals(f.graphArrows.size, 3)
