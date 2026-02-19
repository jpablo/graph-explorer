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

    val request = transition.state match
      case inFlight: InternalPhasesMachine.State.InFlight => inFlight.request
      case _: InternalPhasesMachine.State.Idle             => fail("expected in-flight parse request")
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
      event = InternalPhasesMachine.UiEvent.SourceEdited("""digraph "G" { "a"; }""", DiagramFormat.DOT),
      serializeGraph = InternalPhases.defaultSerializeGraph
    )

    val staleRequest = initialized match
      case inFlight: InternalPhasesMachine.State.InFlight => inFlight.request
      case _: InternalPhasesMachine.State.Idle            => fail("expected stale request")

    val editedInFlight = editedTransition.state match
      case inFlight: InternalPhasesMachine.State.InFlight => inFlight
      case _: InternalPhasesMachine.State.Idle            => fail("expected in-flight state after source edit")

    val staleResultTransition = InternalPhasesMachine.reduce(
      state = editedInFlight,
      event = InternalPhasesMachine.ParseEvent.ParseFailed(
        request = staleRequest,
        error = new RuntimeException("stale"),
        selectedFormat = DiagramFormat.DOT
      )
    )

    assertEquals(staleResultTransition.state, editedTransition.state)
    assertEquals(staleResultTransition.effects, Nil)

  test("step delegates ui events to reduce"):
    val initialized = InternalPhasesMachine.initialize(
      initialText = """digraph "G" {}""",
      initialFormat = DiagramFormat.DOT,
      initialGraph = ViewerGraph.minimalWithDirected,
      initialOrigin = ChangeOrigin.CodeMirror
    ).state

    val uiEvent = InternalPhasesMachine.UiEvent.SourceEdited("""digraph "G" { "a"; }""", DiagramFormat.DOT)
    val viaStep = InternalPhasesMachine.step(
      state = initialized,
      input = InternalPhasesMachine.MachineInput.Ui(uiEvent),
      serializeGraph = InternalPhases.defaultSerializeGraph
    )
    val direct = InternalPhasesMachine.reduce(
      state = initialized,
      event = uiEvent,
      serializeGraph = InternalPhases.defaultSerializeGraph
    )

    assertEquals(viaStep, direct)

  test("step ignores parse events while idle"):
    val initialized = InternalPhasesMachine.initialize(
      initialText = """digraph "G" {}""",
      initialFormat = DiagramFormat.DOT,
      initialGraph = ViewerGraph.minimalWithDirected,
      initialOrigin = ChangeOrigin.CodeMirror
    ).state

    val staleRequest = initialized match
      case inFlight: InternalPhasesMachine.State.InFlight => inFlight.request
      case _: InternalPhasesMachine.State.Idle            => fail("expected in-flight request")

    val idleState = InternalPhasesMachine.initialize(
      initialText = "",
      initialFormat = DiagramFormat.DOT,
      initialGraph = ViewerGraph.minimalWithDirected,
      initialOrigin = ChangeOrigin.CodeMirror
    ).state

    val transition = InternalPhasesMachine.step(
      state = idleState,
      input = InternalPhasesMachine.MachineInput.Parse(
        InternalPhasesMachine.ParseEvent.ParseFailed(
          request = staleRequest,
          error = new RuntimeException("ignored"),
          selectedFormat = DiagramFormat.DOT
        )
      ),
      serializeGraph = InternalPhases.defaultSerializeGraph
    )

    assertEquals(transition.state, idleState)
    assertEquals(transition.effects, Nil)

  test("current parse failure emits editor error and fallback graph"):
    val initialized = InternalPhasesMachine.initialize(
      initialText = """digraph "G" {}""",
      initialFormat = DiagramFormat.DOT,
      initialGraph = ViewerGraph.minimalWithDirected,
      initialOrigin = ChangeOrigin.CodeMirror
    ).state

    val inFlightState = initialized match
      case inFlight: InternalPhasesMachine.State.InFlight => inFlight
      case _: InternalPhasesMachine.State.Idle            => fail("expected in-flight request")

    val failureTransition = InternalPhasesMachine.reduce(
      state = inFlightState,
      event = InternalPhasesMachine.ParseEvent.ParseFailed(
        request = inFlightState.request,
        error = new RuntimeException("boom"),
        selectedFormat = DiagramFormat.DOT
      )
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
      event = InternalPhasesMachine.UiEvent.GraphEdited(graphWithOneNode),
      serializeGraph = (graph, format) => s"${format.toString}:${graph.nodes.size}"
    )

    assertEquals(transition.state.snapshot.text, "DOT:1")
    assertEquals(transition.state.snapshot.lastOrigin, ChangeOrigin.Graph)
    assert(transition.state.isInstanceOf[InternalPhasesMachine.State.Idle], "Graph edit should end in Idle state")
    assertEquals(transition.effects, Nil)
