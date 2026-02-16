package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.ownership.Owner
import com.raquo.airstream.state.{Val, Var}
import com.raquo.laminar.api.L.unsafeWindowOwner
import munit.FunSuite
import org.jpablo.graphexplorer.viewer.backends.{DiagramBackend, DiagramFormat}
import org.jpablo.graphexplorer.viewer.backends.graphviz.SvgWithPositions
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.{ArrowDirection, Attributes, ElementIds}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers

import scala.collection.mutable.Queue
import scala.concurrent.{ExecutionContext, Future, Promise}

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

      phases.sourceText.set("""digraph "G" { broken }""")
      val (_, stalePromise) = dotBackend.popRequest()

      phases.sourceText.set("""digraph "G" { "a"; }""")
      val (_, currentPromise) = dotBackend.popRequest()

      currentPromise.success(graphWithOneNode)
      stalePromise.failure(new RuntimeException("stale parse error"))

      afterMicrotasks {
        assertEquals(editorError.now(), None)
        assertEquals(phases.sourceText.now(), """digraph "G" { "a"; }""")
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

      phases.sourceText.set("""digraph "G" { still broken }""")
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
        assertEquals(phases.sourceText.now(), "SERIALIZED:DOT:1")
      }
    }
