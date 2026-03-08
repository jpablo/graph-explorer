package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.ownership.Owner
import com.raquo.airstream.state.{Val, Var}
import com.raquo.laminar.api.L.unsafeWindowOwner
import munit.FunSuite
import org.jpablo.graphexplorer.viewer.backends.mermaid.{
  MermaidClassAssignmentFallback,
  MermaidClassDef,
  MermaidClassDefFallback,
  MermaidEdge,
  MermaidGraph,
  MermaidMissingVertexFallback,
  MermaidSubgraph,
  MermaidVertex,
  MermaidVertexLabelFallback,
  toViewerGraph
}
import org.jpablo.graphexplorer.viewer.backends.{DiagramBackend, DiagramFormat}
import org.jpablo.graphexplorer.viewer.backends.graphviz.SvgWithPositions
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{FillColor, Label}
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.{ArrowDirection, AttrStatus, AttributeUpdates, Attributes, ElementIds, GroupId, NodeId}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers

import scala.collection.mutable.Queue
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.scalajs.js

class InternalPhasesPhaseSpec extends FunSuite with TestHelpers:

  override def munitFixtures = List(mockStorageFixture())

  given Owner = unsafeWindowOwner
  given ExecutionContext = ExecutionContext.global

  private final class ControlledBackend(val format: DiagramFormat) extends DiagramBackend:
    private val pending = Queue.empty[(String, Promise[ViewerGraph])]

    override def textToGraph(text: String): Future[ViewerGraph] =
      val p = Promise[ViewerGraph]()
      pending.enqueue((text, p))
      p.future

    override def textToSvg(text: String): Future[SvgWithPositions] =
      Future.failed(new UnsupportedOperationException(s"textToSvg is not used by this spec for $format"))

    def popRequest(): (String, Promise[ViewerGraph]) =
      if pending.nonEmpty then pending.dequeue()
      else throw new IllegalStateException(s"No pending parse request for backend $format")

    def pendingCount: Int = pending.size

  private def graphWithOneNode: ViewerGraph =
    val (graph, _, _) = ViewerGraph.minimalWithDirected.addNodeWithSmartConnection(
      selectedElementId = None,
      attributes = Attributes.empty,
      direction = ArrowDirection.forward
    )
    graph

  private def backendResolver(dot: DiagramBackend, mermaid: DiagramBackend): DiagramFormat => DiagramBackend =
    {
      case DiagramFormat.DOT     => dot
      case DiagramFormat.Mermaid => mermaid
    }

  private def applyToolbarLikeFillUpdate(
      graph:   ViewerGraph,
      ids:     ElementIds,
      fillHex: String
  ): ViewerGraph =
    val currentUpdates = graph.getAttributesUpdatesById(ids)
    val nextUpdates    = currentUpdates + (FillColor.attrId -> AttrStatus.Single(AttrValue(fillHex)))
    graph.updateAttributes(ids, nextUpdates)

  private def installJsDom(): Unit =
    js.eval(
      """
        const { JSDOM } = require('jsdom');
        const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/' });
        global.window = dom.window;
        global.document = dom.window.document;
        global.DOMParser = dom.window.DOMParser;
        global.Node = dom.window.Node;
        global.Element = dom.window.Element;
        global.HTMLElement = dom.window.HTMLElement;
        global.SVGElement = dom.window.SVGElement;

        // Defensive DOMPurify bootstrap for Node+jsdom:
        // if Mermaid imported DOMPurify before window existed, sanitize may be missing.
        const dpModule = require('dompurify');
        const DOMPurify = dpModule.default || dpModule;
        if (typeof DOMPurify.sanitize !== 'function') {
          const instance = DOMPurify(dom.window);
          Object.assign(DOMPurify, instance);
        }
      """
    )

  private def waitForCondition(
      condition: => Boolean,
      description: String = "condition",
      timeoutMs: Int = 5000,
      pollMs:    Int = 20
  ): Future[Unit] =
    val p     = Promise[Unit]()
    val start = js.Date.now()
    def loop(): Unit =
      if condition then p.trySuccess(())
      else if js.Date.now() - start >= timeoutMs then
        p.tryFailure(new RuntimeException(s"Timed out after ${timeoutMs}ms waiting for $description"))
      else
        js.timers.setTimeout(pollMs)(loop())
    loop()
    p.future

  test("stale parse failure does not overwrite state or editorError"):
    withGraphvizAsync { graphviz =>
      val dotBackend     = new ControlledBackend(DiagramFormat.DOT)
      val mermaidBackend = new ControlledBackend(DiagramFormat.Mermaid)
      val editorError: Var[Option[String]] = Var(None)
      val phases = InternalPhases(
        graphviz = graphviz,
        hiddenNodes = Val(ElementIds()),
        editorError = editorError,
        backendFor = Some(backendResolver(dotBackend, mermaidBackend))
      )

      // Complete initial parse request
      val (_, initialPromise) = dotBackend.popRequest()
      initialPromise.success(ViewerGraph.minimalWithDirected)

      phases.sourceTextWriter.onNext("""digraph "G" { broken }""")
      val (_, stalePromise) = dotBackend.popRequest()

      phases.sourceTextWriter.onNext("""digraph "G" { "a"; }""")
      val (_, currentPromise) = dotBackend.popRequest()

      currentPromise.success(graphWithOneNode)
      stalePromise.failure(new RuntimeException("stale parse error"))

      afterMicrotasks {
        assertEquals(editorError.now(), None)
        assertEquals(phases.sourceTextNow(), """digraph "G" { "a"; }""")
        assertEquals(phases.fullGraphV.now().nodes.size, 1)
      }
    }

  test("single source edit schedules one parse request through fold"):
    withGraphvizAsync { graphviz =>
      val dotBackend     = new ControlledBackend(DiagramFormat.DOT)
      val mermaidBackend = new ControlledBackend(DiagramFormat.Mermaid)
      val phases = InternalPhases(
        graphviz = graphviz,
        hiddenNodes = Val(ElementIds()),
        backendFor = Some(backendResolver(dotBackend, mermaidBackend))
      )

      val (_, initialPromise) = dotBackend.popRequest()
      initialPromise.success(ViewerGraph.minimalWithDirected)

      phases.sourceTextWriter.onNext("""digraph "G" { "a"; }""")
      assertEquals(dotBackend.pendingCount, 1)

      val (_, parsePromise) = dotBackend.popRequest()
      assertEquals(dotBackend.pendingCount, 0)
      parsePromise.success(graphWithOneNode)

      afterMicrotasks {
        assertEquals(dotBackend.pendingCount, 0)
        assertEquals(phases.fullGraphV.now().nodes.size, 1)
      }
    }

  test("latest parse failure sets editorError and fallback graph"):
    withGraphvizAsync { graphviz =>
      val dotBackend     = new ControlledBackend(DiagramFormat.DOT)
      val mermaidBackend = new ControlledBackend(DiagramFormat.Mermaid)
      val editorError: Var[Option[String]] = Var(None)
      val phases = InternalPhases(
        graphviz = graphviz,
        hiddenNodes = Val(ElementIds()),
        editorError = editorError,
        backendFor = Some(backendResolver(dotBackend, mermaidBackend))
      )

      val (_, initialPromise) = dotBackend.popRequest()
      initialPromise.success(ViewerGraph.minimalWithDirected)

      phases.sourceTextWriter.onNext("""digraph "G" { still broken }""")
      val (_, failingPromise) = dotBackend.popRequest()
      failingPromise.failure(new RuntimeException("boom"))

      afterMicrotasks {
        assertEquals(editorError.now(), Some("boom"))
        assertEquals(phases.fullGraphV.now(), ViewerGraph.minimalWithDirected)
      }
    }

  test("format switch reparses current text using selected backend"):
    withGraphvizAsync { graphviz =>
      val dotBackend     = new ControlledBackend(DiagramFormat.DOT)
      val mermaidBackend = new ControlledBackend(DiagramFormat.Mermaid)
      val sourceText     = """digraph "G" { "a" -> "b"; }"""
      val phases = InternalPhases(
        graphviz = graphviz,
        initialSource = Some(sourceText),
        hiddenNodes = Val(ElementIds()),
        backendFor = Some(backendResolver(dotBackend, mermaidBackend))
      )

      val (_, initialPromise) = dotBackend.popRequest()
      initialPromise.success(ViewerGraph.minimalWithDirected)

      phases.formatSelection.set(DiagramFormat.Mermaid)
      val (mermaidText, mermaidPromise) = mermaidBackend.popRequest()
      assertEquals(mermaidText, sourceText)
      mermaidPromise.success(graphWithOneNode)

      afterMicrotasks {
        assertEquals(phases.currentFormat.observe.now(), DiagramFormat.Mermaid)
        assertEquals(phases.fullGraphV.now().nodes.size, 1)
      }
    }

  test("graph updates use injected serializer for current format"):
    withGraphvizAsync { graphviz =>
      val dotBackend     = new ControlledBackend(DiagramFormat.DOT)
      val mermaidBackend = new ControlledBackend(DiagramFormat.Mermaid)
      val phases = InternalPhases(
        graphviz = graphviz,
        hiddenNodes = Val(ElementIds()),
        backendFor = Some(backendResolver(dotBackend, mermaidBackend)),
        serializeGraph = (graph, format) => s"SERIALIZED:${format.toString}:${graph.nodes.size}"
      )

      val (_, initialPromise) = dotBackend.popRequest()
      initialPromise.success(ViewerGraph.minimalWithDirected)

      phases.fullGraphV.set(graphWithOneNode)

      afterMicrotasks {
        assertEquals(phases.sourceTextNow(), "SERIALIZED:DOT:1")
      }
    }

  test("mermaid end-to-end internal phases pipeline preserves labels/classes after node fill edit"):
    withGraphvizAsync { graphviz =>
      val dotBackend     = new ControlledBackend(DiagramFormat.DOT)
      val mermaidBackend = new ControlledBackend(DiagramFormat.Mermaid)

      val source =
        """flowchart LR
          |subgraph G1 [Service Layer]
          |  A[CodeMirror]
          |  B[Parser]
          |end
          |A -->|parses| B
          |classDef default fill:#fefecc,stroke:#85df72
          |classDef pink fill:#ff66cc,stroke:#aa0099,color:#ffffff
          |class G1 pink
          |class A pink
          |linkStyle default stroke:#0044ff,stroke-width:2px
          |""".stripMargin

      val phases = InternalPhases(
        graphviz = graphviz,
        initialSource = Some(source),
        hiddenNodes = Val(ElementIds()),
        backendFor = Some(backendResolver(dotBackend, mermaidBackend))
      )

      val (requestedText, parsePromise) = mermaidBackend.popRequest()
      assertEquals(requestedText, source)

      val parsedGraph = toViewerGraph(
        MermaidGraph(
          // Simulate parser output where dictionary keys are graph ids but vertex.id is DOM-like.
          vertices = Map(
            "A" -> MermaidVertex(id = "flowchart-A-0", text = "CodeMirror", classes = List("pink")),
            "B" -> MermaidVertex(id = "flowchart-B-0", text = "Parser")
          ),
          edges = List(MermaidEdge(start = "A", end = "B", text = Some("parses"))),
          subgraphs = List(
            MermaidSubgraph(id = "G1", title = Some("Service Layer"), nodes = List("A", "B"), classes = List("pink"))
          ),
          direction = Some("LR"),
          classDefs = Map(
            "default" -> MermaidClassDef(styles = List("fill:#fefecc", "stroke:#85df72")),
            "pink"    -> MermaidClassDef(styles = List("fill:#ff66cc", "stroke:#aa0099", "color:#ffffff"))
          ),
          defaultEdgeStyle = List("stroke:#0044ff", "stroke-width:2px")
        )
      )
      parsePromise.success(parsedGraph)

      for
        _ <- afterMicrotasks {
          phases.updateFullGraph(
            _.updateAttributes(
              ElementIds.from(NodeId("B")),
              AttributeUpdates.of(FillColor -> "#ffc9c9")
            )
          )
        }
        _ <- afterMicrotasks {
          val serialized = phases.sourceTextNow()
          assert(serialized.contains("A[CodeMirror]"), s"A label should be preserved, got: $serialized")
          assert(serialized.contains("B[Parser]"), s"B label should be preserved, got: $serialized")
          assert(serialized.contains("class G1 pink"), s"Group class should be preserved, got: $serialized")
          assert(
            serialized.contains("A[CodeMirror]:::pink") || serialized.contains("class A pink"),
            s"Node A class should be preserved, got: $serialized"
          )
          assert(serialized.contains("style B fill:#ffc9c9"), s"Edited B fill should be serialized, got: $serialized")
        }
      yield ()
    }

  test("mermaid end-to-end internal phases pipeline with runtime-like payload preserves labels/classes after node fill edit"):
    withGraphvizAsync { graphviz =>
      val dotBackend     = new ControlledBackend(DiagramFormat.DOT)
      val mermaidBackend = new ControlledBackend(DiagramFormat.Mermaid)
      val source =
        """flowchart LR
          |subgraph G1 [Service Layer]
          |  A[CodeMirror]
          |  B[Parser]
          |end
          |A -->|parses| B
          |classDef default fill:#fefecc,stroke:#85df72
          |classDef pink fill:#ff66cc,stroke:#aa0099,color:#ffffff
          |class G1 pink
          |class A pink
          |linkStyle default stroke:#0044ff,stroke-width:2px
          |""".stripMargin

      val phases = InternalPhases(
        graphviz = graphviz,
        initialSource = Some(source),
        hiddenNodes = Val(ElementIds()),
        backendFor = Some(backendResolver(dotBackend, mermaidBackend))
      )

      val (requestedText, parsePromise) = mermaidBackend.popRequest()
      assertEquals(requestedText, source)

      val rawEdges = List(MermaidEdge(start = "A", end = "B", text = Some("parses")))
      val rawSubgraphs = List(
        MermaidSubgraph(id = "G1", title = Some("Service Layer"), nodes = List("A", "B"), classes = List("pink"))
      )

      // Simulate Mermaid 11 payload shape observed in practice:
      // - db.getVertices() = {}
      // - db.getClasses()  = {}
      val (verticesWithSourceClasses, subgraphsWithSourceClasses) =
        MermaidClassAssignmentFallback.withSourceClassAssignments(source, Map.empty, rawSubgraphs)
      val verticesWithSourceLabels =
        MermaidVertexLabelFallback.withSourceVertexLabels(source, verticesWithSourceClasses)
      val verticesWithSourceCoverage =
        MermaidMissingVertexFallback.withSourceVertices(source, verticesWithSourceLabels, rawEdges, subgraphsWithSourceClasses)
      val classDefs = MermaidClassDefFallback.withSourceClassDefs(source, Map.empty)

      val parsedGraph = toViewerGraph(
        MermaidGraph(
          vertices = verticesWithSourceCoverage,
          edges = rawEdges,
          subgraphs = subgraphsWithSourceClasses,
          direction = Some("LR"),
          classDefs = classDefs,
          defaultEdgeStyle = List("stroke:#0044ff", "stroke-width:2px")
        )
      )
      parsePromise.success(parsedGraph)

      for
        _ <- afterMicrotasks {
          phases.updateFullGraph(
            _.updateAttributes(
              ElementIds.from(NodeId("B")),
              AttributeUpdates.of(FillColor -> "#fb2c36")
            )
          )
        }
        _ <- afterMicrotasks {
          val serialized = phases.sourceTextNow()
          assert(serialized.contains("A[CodeMirror]"), s"A label should be preserved, got: $serialized")
          assert(serialized.contains("B[Parser]"), s"B label should be preserved, got: $serialized")
          assert(serialized.contains("class G1 pink"), s"Group class should be preserved, got: $serialized")
          assert(
            serialized.contains("A[CodeMirror]:::pink") || serialized.contains("class A pink"),
            s"Node A class should be preserved, got: $serialized"
          )
        }
      yield ()
    }

  test("mermaid end-to-end with toolbar-like update path preserves labels/classes for opaque vertex ids"):
    withGraphvizAsync { graphviz =>
      val dotBackend     = new ControlledBackend(DiagramFormat.DOT)
      val mermaidBackend = new ControlledBackend(DiagramFormat.Mermaid)
      val source =
        """flowchart LR
          |subgraph G1 [Service Layer]
          |  A[CodeMirror]
          |  B[Parser]
          |end
          |A -->|parses| B
          |classDef default fill:#fefecc,stroke:#85df72
          |classDef pink fill:#ff66cc,stroke:#aa0099,color:#ffffff
          |class G1 pink
          |class A pink
          |linkStyle default stroke:#0044ff,stroke-width:2px
          |""".stripMargin

      val phases = InternalPhases(
        graphviz = graphviz,
        initialSource = Some(source),
        hiddenNodes = Val(ElementIds()),
        backendFor = Some(backendResolver(dotBackend, mermaidBackend))
      )

      val (requestedText, parsePromise) = mermaidBackend.popRequest()
      assertEquals(requestedText, source)

      val rawEdges = List(MermaidEdge(start = "A", end = "B", text = Some("parses")))
      val rawSubgraphs = List(
        MermaidSubgraph(id = "G1", title = Some("Service Layer"), nodes = List("A", "B"), classes = List("pink"))
      )
      // Simulate a hostile parser payload where the vertex map only exposes opaque DOM-like ids.
      val rawVertices = Map(
        "flowchart-A-0" -> MermaidVertex(id = "flowchart-A-0", text = "CodeMirror"),
        "flowchart-B-0" -> MermaidVertex(id = "flowchart-B-0", text = "Parser")
      )
      val (verticesWithSourceClasses, subgraphsWithSourceClasses) =
        MermaidClassAssignmentFallback.withSourceClassAssignments(source, rawVertices, rawSubgraphs)
      val verticesWithSourceLabels =
        MermaidVertexLabelFallback.withSourceVertexLabels(source, verticesWithSourceClasses)
      val verticesWithSourceCoverage =
        MermaidMissingVertexFallback.withSourceVertices(source, verticesWithSourceLabels, rawEdges, subgraphsWithSourceClasses)
      val classDefs = MermaidClassDefFallback.withSourceClassDefs(source, Map.empty)

      parsePromise.success(
        toViewerGraph(
          MermaidGraph(
            vertices = verticesWithSourceCoverage,
            edges = rawEdges,
            subgraphs = subgraphsWithSourceClasses,
            direction = Some("LR"),
            classDefs = classDefs,
            defaultEdgeStyle = List("stroke:#0044ff", "stroke-width:2px")
          )
        )
      )

      for
        _ <- afterMicrotasks {
          phases.updateFullGraph(graph => applyToolbarLikeFillUpdate(graph, ElementIds.from(NodeId("B")), "#fb2c36"))
        }
        _ <- afterMicrotasks {
          val serialized = phases.sourceTextNow()
          assert(serialized.contains("A[CodeMirror]"), s"A label should be preserved, got: $serialized")
          assert(serialized.contains("B[Parser]"), s"B label should be preserved, got: $serialized")
          assert(serialized.contains("class G1 pink"), s"Group class should be preserved, got: $serialized")
          assert(
            serialized.contains("A[CodeMirror]:::pink") || serialized.contains("class A pink"),
            s"Node A class should be preserved, got: $serialized"
          )
          assert(serialized.contains("style B fill:#fb2c36"), s"Edited B fill should be serialized, got: $serialized")
          assert(!serialized.contains("style G1 fill:#fb2c36"), s"G1 should not inherit B fill edit, got: $serialized")
        }
      yield ()
    }

  test("mermaid toolbar-like mixed selection can mutate both node and group (documenting current behavior)"):
    withGraphvizAsync { graphviz =>
      val dotBackend     = new ControlledBackend(DiagramFormat.DOT)
      val mermaidBackend = new ControlledBackend(DiagramFormat.Mermaid)
      val source =
        """flowchart LR
          |subgraph G1 [Service Layer]
          |  A[CodeMirror]
          |  B[Parser]
          |end
          |A -->|parses| B
          |classDef default fill:#fefecc,stroke:#85df72
          |classDef pink fill:#ff66cc,stroke:#aa0099,color:#ffffff
          |class G1 pink
          |class A pink
          |linkStyle default stroke:#0044ff,stroke-width:2px
          |""".stripMargin

      val phases = InternalPhases(
        graphviz = graphviz,
        initialSource = Some(source),
        hiddenNodes = Val(ElementIds()),
        backendFor = Some(backendResolver(dotBackend, mermaidBackend))
      )

      val (requestedText, parsePromise) = mermaidBackend.popRequest()
      assertEquals(requestedText, source)

      val rawEdges = List(MermaidEdge(start = "A", end = "B", text = Some("parses")))
      val rawSubgraphs = List(
        MermaidSubgraph(id = "G1", title = Some("Service Layer"), nodes = List("A", "B"), classes = List("pink"))
      )
      val (verticesWithSourceClasses, subgraphsWithSourceClasses) =
        MermaidClassAssignmentFallback.withSourceClassAssignments(source, Map.empty, rawSubgraphs)
      val verticesWithSourceLabels =
        MermaidVertexLabelFallback.withSourceVertexLabels(source, verticesWithSourceClasses)
      val verticesWithSourceCoverage =
        MermaidMissingVertexFallback.withSourceVertices(source, verticesWithSourceLabels, rawEdges, subgraphsWithSourceClasses)
      val classDefs = MermaidClassDefFallback.withSourceClassDefs(source, Map.empty)

      parsePromise.success(
        toViewerGraph(
          MermaidGraph(
            vertices = verticesWithSourceCoverage,
            edges = rawEdges,
            subgraphs = subgraphsWithSourceClasses,
            direction = Some("LR"),
            classDefs = classDefs,
            defaultEdgeStyle = List("stroke:#0044ff", "stroke-width:2px")
          )
        )
      )

      for
        _ <- afterMicrotasks {
          val mixedSelection = ElementIds(Set(NodeId("B"), GroupId("G1")))
          phases.updateFullGraph(graph => applyToolbarLikeFillUpdate(graph, mixedSelection, "#fb2c36"))
        }
        _ <- afterMicrotasks {
          val serialized = phases.sourceTextNow()
          assert(serialized.contains("style B fill:#fb2c36"), s"B fill should be edited, got: $serialized")
          assert(serialized.contains("style G1 fill:#fb2c36"), s"Mixed selection should also style G1, got: $serialized")
        }
      yield ()
    }

  test("mermaid end-to-end internal phases pipeline with real MermaidBackend under jsdom"):
    installJsDom()
    withGraphvizAsync { graphviz =>
      val dotBackend     = new ControlledBackend(DiagramFormat.DOT)
      val mermaidBackend = new org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidBackend()
      val editorError: Var[Option[String]] = Var(None)
      val source =
        """flowchart LR
          |subgraph G1 [Service Layer]
          |  A[CodeMirror]
          |  B[Parser]
          |end
          |A -->|parses| B
          |classDef default fill:#fefecc,stroke:#85df72
          |classDef pink fill:#ff66cc,stroke:#aa0099,color:#ffffff
          |class G1 pink
          |class A pink
          |linkStyle default stroke:#0044ff,stroke-width:2px
          |""".stripMargin

      val phases = InternalPhases(
        graphviz = graphviz,
        initialSource = Some(source),
        hiddenNodes = Val(ElementIds()),
        editorError = editorError,
        backendFor = Some(backendResolver(dotBackend, mermaidBackend)),
        logLevel = org.jpablo.graphexplorer.viewer.logging.Level.Info
      )
      val observedGraph = Var(ViewerGraph.minimalWithDirected)
      phases.fullGraph.foreach(observedGraph.set(_))

      val parserNodeId = Var(Option.empty[NodeId])

      for
        _ <- waitForCondition(
          condition =
            (
              observedGraph.now().nodes.nonEmpty &&
                observedGraph.now().arrows.nonEmpty
            ) || editorError.now().nonEmpty,
          description = "real-backend parse to produce non-empty graph or editorError",
          timeoutMs = 15000
        )
        _ <- afterMicrotasks {
          editorError.now().foreach(msg => fail(s"Real Mermaid backend parse failed in headless test: $msg"))
          val graph = observedGraph.now()
          val summary =
            s"Real-backend parse graph summary: nodes=${graph.nodes.size}, arrows=${graph.arrows.size}, groups=${graph.groups.size}"
          assert(
            graph.nodes.nonEmpty && graph.arrows.nonEmpty,
            s"$summary; source=${phases.sourceTextNow()}"
          )
        }
        _ <- afterMicrotasks {
          val graph = observedGraph.now()
          val parserId =
            graph.nodes.collectFirst {
              case (id, node) if node.attributes.values.get(Label.attrId).exists(_.toString == "Parser") => id
            }.orElse(
              if graph.nodes.contains(NodeId("B")) then Some(NodeId("B")) else None
            )
              .getOrElse(fail(s"Parser node not found in parsed graph. Nodes: ${graph.nodes.keys.map(_.value).mkString(", ")}"))
          parserNodeId.set(Some(parserId))
        }
        _ <- afterMicrotasks {
          phases.updateFullGraph(graph => applyToolbarLikeFillUpdate(graph, ElementIds.from(parserNodeId.now().get), "#fb2c36"))
        }
        _ <- waitForCondition(
          condition = phases.sourceTextNow().contains(s"style ${parserNodeId.now().get.value} fill:#fb2c36"),
          description = "serialized source to include parser fill style",
          timeoutMs = 5000
        )
        _ <- afterMicrotasks {
          val serialized = phases.sourceTextNow()
          assert(serialized.contains("CodeMirror"), s"CodeMirror label should be preserved, got: $serialized")
          assert(serialized.contains("Parser"), s"Parser label should be preserved, got: $serialized")
          assert(serialized.contains("class G1 pink"), s"Group class should be preserved, got: $serialized")
          assert(serialized.contains(s"style ${parserNodeId.now().get.value} fill:#fb2c36"), s"Edited parser fill should be serialized, got: $serialized")
        }
      yield ()
    }

  test("mermaid full phases currently reproduces synthetic subgraph node line after node fill edit"):
    installJsDom()
    withGraphvizAsync { graphviz =>
      val dotBackend     = new ControlledBackend(DiagramFormat.DOT)
      val mermaidBackend = new org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidBackend()
      val editorError: Var[Option[String]] = Var(None)
      val source =
        """flowchart LR
          |subgraph G1 [Service Layer]
          |  A[CodeMirror]
          |  B[Parser]
          |end
          |A -->|parses| B
          |classDef default fill:#fefecc,stroke:#85df72
          |classDef pink fill:#ff66cc,stroke:#aa0099,color:#ffffff
          |class G1 pink
          |class A pink
          |linkStyle default stroke:#0044ff,stroke-width:2px
          |""".stripMargin

      val phases = InternalPhases(
        graphviz = graphviz,
        initialSource = Some(source),
        hiddenNodes = Val(ElementIds()),
        editorError = editorError,
        backendFor = Some(backendResolver(dotBackend, mermaidBackend))
      )
      val observedGraph = Var(ViewerGraph.minimalWithDirected)
      phases.fullGraph.foreach(observedGraph.set(_))

      val parserNodeId = Var(Option.empty[NodeId])

      for
        _ <- waitForCondition(
          condition =
            (
              observedGraph.now().nodes.nonEmpty &&
                observedGraph.now().arrows.nonEmpty
            ) || editorError.now().nonEmpty,
          description = "real-backend parse to produce non-empty graph or editorError",
          timeoutMs = 15000
        )
        _ <- afterMicrotasks {
          editorError.now().foreach(msg => fail(s"Real Mermaid backend parse failed in headless test: $msg"))
          val graph = observedGraph.now()
          val parserId =
            graph.nodes.collectFirst {
              case (id, node) if node.attributes.values.get(Label.attrId).exists(_.toString == "Parser") => id
            }.orElse(
              if graph.nodes.contains(NodeId("B")) then Some(NodeId("B")) else None
            )
              .getOrElse(fail(s"Parser node not found in parsed graph. Nodes: ${graph.nodes.keys.map(_.value).mkString(", ")}"))
          parserNodeId.set(Some(parserId))
        }
        _ <- afterMicrotasks {
          phases.updateFullGraph(graph => applyToolbarLikeFillUpdate(graph, ElementIds.from(parserNodeId.now().get), "#fb2c36"))
        }
        _ <- waitForCondition(
          condition = phases.sourceTextNow().contains(s"style ${parserNodeId.now().get.value} fill:#fb2c36"),
          description = "serialized source to include parser fill style",
          timeoutMs = 5000
        )
        _ <- afterMicrotasks {
          val serialized         = phases.sourceTextNow()
          val hasSyntheticG1Node = serialized.linesIterator.map(_.trim).contains("G1:::pink")
          assert(
            hasSyntheticG1Node,
            s"Expected repro to include synthetic `G1:::pink` line, got: $serialized"
          )
        }
      yield ()
    }
