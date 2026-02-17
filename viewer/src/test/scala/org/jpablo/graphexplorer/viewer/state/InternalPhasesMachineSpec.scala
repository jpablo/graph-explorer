package org.jpablo.graphexplorer.viewer.state

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.{ArrowDirection, Attributes}
import org.jpablo.graphexplorer.viewer.utils.ChangeOrigin

class InternalPhasesMachineSpec extends FunSuite:

  private def graphWithOneNode: ViewerGraph =
    val (graph, _, _) = ViewerGraph.minimalWithDirected.addNodeWithSmartConnection(
      selectedElementId = None,
      attributes = Attributes.empty,
      direction = ArrowDirection.forward
    )
    graph

  test("initialize schedules parse request for non-empty text"):
    val transition = InternalPhasesMachine.initialize(
      initialText = """digraph "G" {}""",
      initialFormat = DiagramFormat.DOT,
      initialGraph = ViewerGraph.minimalWithDirected,
      initialOrigin = ChangeOrigin.CodeMirror
    )

    val request = transition.state.inFlightParse.getOrElse(fail("expected in-flight parse request"))
    assertEquals(request.id, 1L)
    assertEquals(request.format, DiagramFormat.DOT)
    assertEquals(transition.effects, List(InternalPhasesMachine.Effect.StartParse(request)))

  test("stale parse completion is ignored"):
    val initialized = InternalPhasesMachine.initialize(
      initialText = """digraph "G" {}""",
      initialFormat = DiagramFormat.DOT,
      initialGraph = ViewerGraph.minimalWithDirected,
      initialOrigin = ChangeOrigin.CodeMirror
    ).state

    val editedTransition = InternalPhasesMachine.reduce(
      state = initialized,
      event = InternalPhasesMachine.Event.SourceEdited("""digraph "G" { "a"; }""", DiagramFormat.DOT),
      serializeGraph = InternalPhases.defaultSerializeGraph
    )

    val staleRequest = initialized.inFlightParse.getOrElse(fail("expected stale request"))
    val staleResultTransition = InternalPhasesMachine.reduce(
      state = editedTransition.state,
      event = InternalPhasesMachine.Event.ParseCompleted(
        request = staleRequest,
        result = Left(new RuntimeException("stale")),
        selectedFormat = DiagramFormat.DOT
      ),
      serializeGraph = InternalPhases.defaultSerializeGraph
    )

    assertEquals(staleResultTransition.state, editedTransition.state)
    assertEquals(staleResultTransition.effects, Nil)

  test("current parse failure emits editor error and fallback graph"):
    val initialized = InternalPhasesMachine.initialize(
      initialText = """digraph "G" {}""",
      initialFormat = DiagramFormat.DOT,
      initialGraph = ViewerGraph.minimalWithDirected,
      initialOrigin = ChangeOrigin.CodeMirror
    ).state

    val request = initialized.inFlightParse.getOrElse(fail("expected in-flight request"))
    val failureTransition = InternalPhasesMachine.reduce(
      state = initialized,
      event = InternalPhasesMachine.Event.ParseCompleted(
        request = request,
        result = Left(new RuntimeException("boom")),
        selectedFormat = DiagramFormat.DOT
      ),
      serializeGraph = InternalPhases.defaultSerializeGraph
    )

    assertEquals(failureTransition.state.snapshot.viewerGraph, ViewerGraph.minimalWithDirected)
    assertEquals(failureTransition.effects, List(InternalPhasesMachine.Effect.SetEditorError(Some("boom"))))

  test("graph edit updates text via serializer and clears in-flight parse"):
    val initialized = InternalPhasesMachine.initialize(
      initialText = """digraph "G" {}""",
      initialFormat = DiagramFormat.DOT,
      initialGraph = ViewerGraph.minimalWithDirected,
      initialOrigin = ChangeOrigin.CodeMirror
    ).state

    val transition = InternalPhasesMachine.reduce(
      state = initialized,
      event = InternalPhasesMachine.Event.GraphEdited(graphWithOneNode),
      serializeGraph = (graph, format) => s"${format.toString}:${graph.nodes.size}"
    )

    assertEquals(transition.state.snapshot.text, "DOT:1")
    assertEquals(transition.state.snapshot.lastOrigin, ChangeOrigin.Graph)
    assertEquals(transition.state.inFlightParse, None)
    assertEquals(transition.effects, Nil)

