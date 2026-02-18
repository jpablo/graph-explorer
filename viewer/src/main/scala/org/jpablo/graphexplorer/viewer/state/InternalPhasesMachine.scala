package org.jpablo.graphexplorer.viewer.state

import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.utils.ChangeOrigin

object InternalPhasesMachine:

  final case class InFlightRequest private[InternalPhasesMachine] (
      id:     Long,
      text:   String,
      format: DiagramFormat,
      origin: ChangeOrigin
  ) derives CanEqual

  enum Effect derives CanEqual:
    case StartParse(request: InFlightRequest)
    case SetEditorError(error: Option[String])

  sealed trait State derives CanEqual:
    def snapshot: GraphState
    def nextRequestId: Long

  object State:
    final case class Idle(snapshot: GraphState, nextRequestId: Long) extends State
    final case class InFlight(snapshot: GraphState, request: InFlightRequest, nextRequestId: Long) extends State

  enum UiEvent:
    case SourceEdited(newText: String, selectedFormat: DiagramFormat)
    case GraphEdited(newGraph: ViewerGraph)
    case FormatChanged(newFormat: DiagramFormat)

  enum ParseEvent:
    case ParseSucceeded(request: InFlightRequest, graph: ViewerGraph, selectedFormat: DiagramFormat)
    case ParseFailed(request: InFlightRequest, error: Throwable, selectedFormat: DiagramFormat)

  case class Transition[+S <: State](
      state:   S,
      effects: List[Effect]
  )

  def initialize(
      initialText:   String,
      initialFormat: DiagramFormat,
      initialGraph:  ViewerGraph,
      initialOrigin: ChangeOrigin
  ): Transition[State] =
    val initialState = State.Idle(
      snapshot = GraphState(
        text = initialText,
        viewerGraph = initialGraph,
        format = initialFormat,
        lastOrigin = initialOrigin
      ),
      nextRequestId = 1L
    )
    scheduleParseIfNeeded(initialState, initialText, initialFormat, initialOrigin)

  def reduce(
      state:          State,
      event:          UiEvent,
      serializeGraph: (ViewerGraph, DiagramFormat) => String
  ): Transition[State] =
    event match
      case UiEvent.SourceEdited(newText, selectedFormat) =>
        if newText == state.snapshot.text then
          Transition(state, Nil)
        else
          val nextState = toIdle(
            state,
            state.snapshot.copy(
              text = newText,
              format = selectedFormat,
              lastOrigin = ChangeOrigin.CodeMirror
            )
          )
          scheduleParseIfNeeded(nextState, newText, selectedFormat, ChangeOrigin.CodeMirror)

      case UiEvent.GraphEdited(newGraph) =>
        if newGraph == state.snapshot.viewerGraph then
          Transition(state, Nil)
        else
          val serializedText = serializeGraph(newGraph, state.snapshot.format)
          Transition(
            toIdle(
              state,
              GraphState(
                text = serializedText,
                viewerGraph = newGraph,
                format = state.snapshot.format,
                lastOrigin = ChangeOrigin.Graph
              )
            ),
            Nil
          )

      case UiEvent.FormatChanged(newFormat) =>
        if state.snapshot.format == newFormat then
          Transition(state, Nil)
        else
          val nextSnapshot = state.snapshot.copy(format = newFormat)
          val nextState    = toIdle(state, nextSnapshot)
          scheduleParseIfNeeded(nextState, nextSnapshot.text, newFormat, nextSnapshot.lastOrigin)

  // Parse results can only be reduced from InFlight state.
  def reduce(state: State.InFlight, event: ParseEvent): Transition[State] =
    event match
      case ParseEvent.ParseSucceeded(request, graph, selectedFormat) =>
        if !isCurrentParse(state, request, selectedFormat) then
          Transition(state, Nil)
        else
          Transition(
            State.Idle(
              snapshot = GraphState(
                text = request.text,
                viewerGraph = graph,
                format = request.format,
                lastOrigin = request.origin
              ),
              nextRequestId = state.nextRequestId
            ),
            List(Effect.SetEditorError(None))
          )

      case ParseEvent.ParseFailed(request, error, selectedFormat) =>
        if !isCurrentParse(state, request, selectedFormat) then
          Transition(state, Nil)
        else
          Transition(
            State.Idle(
              snapshot = GraphState(
                text = request.text,
                viewerGraph = ViewerGraph.minimalWithDirected,
                format = request.format,
                lastOrigin = request.origin
              ),
              nextRequestId = state.nextRequestId
            ),
            List(Effect.SetEditorError(Option(error.getMessage)))
          )

  def describeTransition(
      before:     State,
      event:      UiEvent,
      transition: Transition[State]
  ): String =
    describeTransitionWithLabel(before, uiEventLabel(event), transition)

  def describeTransition(
      before:     State.InFlight,
      event:      ParseEvent,
      transition: Transition[State]
  ): String =
    describeTransitionWithLabel(before, parseEventLabel(event), transition)

  private def describeTransitionWithLabel(
      before:     State,
      eventLabel: String,
      transition: Transition[State]
  ): String =
    val after              = transition.state
    val parseBefore        = parseStateLabel(before)
    val parseAfter         = parseStateLabel(after)
    val snapshotChanged    = before.snapshot != after.snapshot
    val effectsDescription = transition.effects.map(effectLabel).mkString("[", ", ", "]")
    s"transition event=$eventLabel parse=$parseBefore->$parseAfter snapshotChanged=$snapshotChanged effects=$effectsDescription"

  private def scheduleParseIfNeeded(
      state:  State.Idle,
      text:   String,
      format: DiagramFormat,
      origin: ChangeOrigin
  ): Transition[State] =
    if text.trim.nonEmpty then
      val request = InFlightRequest(
        id = state.nextRequestId,
        text = text,
        format = format,
        origin = origin
      )
      Transition(
        State.InFlight(
          snapshot = state.snapshot,
          request = request,
          nextRequestId = state.nextRequestId + 1
        ),
        List(Effect.StartParse(request))
      )
    else
      Transition(state, Nil)

  private def isCurrentParse(state: State.InFlight, request: InFlightRequest, selectedFormat: DiagramFormat): Boolean =
    state.request.id == request.id &&
      state.snapshot.text == request.text &&
      selectedFormat == request.format

  private def toIdle(state: State, snapshot: GraphState): State.Idle =
    State.Idle(
      snapshot = snapshot,
      nextRequestId = state.nextRequestId
    )

  private def parseStateLabel(state: State): String =
    state match
      case _: State.Idle            => "Idle"
      case inFlight: State.InFlight => s"InFlight#${inFlight.request.id}"

  private def uiEventLabel(event: UiEvent): String =
    event match
      case UiEvent.SourceEdited(_, selectedFormat) =>
        s"SourceEdited(format=$selectedFormat)"
      case UiEvent.GraphEdited(_) =>
        "GraphEdited"
      case UiEvent.FormatChanged(newFormat) =>
        s"FormatChanged(new=$newFormat)"

  private def parseEventLabel(event: ParseEvent): String =
    event match
      case ParseEvent.ParseSucceeded(request, _, selectedFormat) =>
        s"ParseSucceeded(id=${request.id}, selected=$selectedFormat, requestFormat=${request.format})"
      case ParseEvent.ParseFailed(request, _, selectedFormat) =>
        s"ParseFailed(id=${request.id}, selected=$selectedFormat, requestFormat=${request.format})"

  private def effectLabel(effect: Effect): String =
    effect match
      case Effect.StartParse(request)      => s"StartParse#${request.id}:${request.format}"
      case Effect.SetEditorError(None)     => "SetEditorError(None)"
      case Effect.SetEditorError(Some(_))  => "SetEditorError(Some)"

end InternalPhasesMachine
