package org.jpablo.graphexplorer.viewer.state

import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.utils.ChangeOrigin

object InternalPhasesMachine:

  case class ParseRequest(
      id:     Long,
      text:   String,
      format: DiagramFormat,
      origin: ChangeOrigin
  ) derives CanEqual

  enum Effect derives CanEqual:
    case StartParse(request: ParseRequest)
    case SetEditorError(error: Option[String])

  enum Event:
    case SourceEdited(newText: String, selectedFormat: DiagramFormat)
    case GraphEdited(newGraph: ViewerGraph)
    case FormatChanged(newFormat: DiagramFormat)
    case ParseCompleted(request: ParseRequest, result: Either[Throwable, ViewerGraph], selectedFormat: DiagramFormat)

  case class State(
      snapshot:      GraphState,
      inFlightParse: Option[ParseRequest],
      nextRequestId: Long
  ) derives CanEqual

  case class Transition(
      state:   State,
      effects: List[Effect]
  )

  def describeTransition(
      before:     State,
      event:      Event,
      transition: Transition
  ): String =
    val after              = transition.state
    val parseBefore        = parseStateLabel(before.inFlightParse)
    val parseAfter         = parseStateLabel(after.inFlightParse)
    val snapshotChanged    = before.snapshot != after.snapshot
    val effectsDescription = transition.effects.map(effectLabel).mkString("[", ", ", "]")
    s"transition event=${eventLabel(event)} parse=$parseBefore->$parseAfter snapshotChanged=$snapshotChanged effects=$effectsDescription"

  def initialize(
      initialText:    String,
      initialFormat:  DiagramFormat,
      initialGraph:   ViewerGraph,
      initialOrigin:  ChangeOrigin
  ): Transition =
    val initialState = State(
      snapshot = GraphState(
        text = initialText,
        viewerGraph = initialGraph,
        format = initialFormat,
        lastOrigin = initialOrigin
      ),
      inFlightParse = None,
      nextRequestId = 1L
    )
    scheduleParseIfNeeded(initialState, initialText, initialFormat, initialOrigin)

  def reduce(
      state:          State,
      event:          Event,
      serializeGraph: (ViewerGraph, DiagramFormat) => String
  ): Transition =
    event match
      case Event.SourceEdited(newText, selectedFormat) =>
        if newText == state.snapshot.text then
          Transition(state, Nil)
        else
          val nextState = state.copy(
            snapshot = state.snapshot.copy(
              text = newText,
              format = selectedFormat,
              lastOrigin = ChangeOrigin.CodeMirror
            )
          )
          scheduleParseIfNeeded(nextState, newText, selectedFormat, ChangeOrigin.CodeMirror)

      case Event.GraphEdited(newGraph) =>
        if newGraph == state.snapshot.viewerGraph then
          Transition(state, Nil)
        else
          val serializedText = serializeGraph(newGraph, state.snapshot.format)
          Transition(
            state.copy(
              snapshot = GraphState(
                text = serializedText,
                viewerGraph = newGraph,
                format = state.snapshot.format,
                lastOrigin = ChangeOrigin.Graph
              ),
              inFlightParse = None
            ),
            Nil
          )

      case Event.FormatChanged(newFormat) =>
        if state.snapshot.format == newFormat then
          Transition(state, Nil)
        else
          val nextSnapshot = state.snapshot.copy(format = newFormat)
          val nextState    = state.copy(snapshot = nextSnapshot)
          scheduleParseIfNeeded(nextState, nextSnapshot.text, newFormat, nextSnapshot.lastOrigin)

      case Event.ParseCompleted(request, result, selectedFormat) =>
        val isCurrent =
          state.inFlightParse.exists(_.id == request.id) &&
            state.snapshot.text == request.text &&
            selectedFormat == request.format

        if !isCurrent then
          Transition(state, Nil)
        else
          result match
            case Right(graph) =>
              Transition(
                state.copy(
                  snapshot = GraphState(
                    text = request.text,
                    viewerGraph = graph,
                    format = request.format,
                    lastOrigin = request.origin
                  ),
                  inFlightParse = None
                ),
                List(Effect.SetEditorError(None))
              )

            case Left(error) =>
              Transition(
                state.copy(
                  snapshot = GraphState(
                    text = request.text,
                    viewerGraph = ViewerGraph.minimalWithDirected,
                    format = request.format,
                    lastOrigin = request.origin
                  ),
                  inFlightParse = None
                ),
                List(Effect.SetEditorError(Option(error.getMessage)))
              )

  private def scheduleParseIfNeeded(
      state:   State,
      text:    String,
      format:  DiagramFormat,
      origin:  ChangeOrigin
  ): Transition =
    if text.trim.nonEmpty then
      val request = ParseRequest(
        id = state.nextRequestId,
        text = text,
        format = format,
        origin = origin
      )
      Transition(
        state.copy(
          inFlightParse = Some(request),
          nextRequestId = state.nextRequestId + 1
        ),
        List(Effect.StartParse(request))
      )
    else
      Transition(state.copy(inFlightParse = None), Nil)

  private def parseStateLabel(parse: Option[ParseRequest]): String =
    parse match
      case None          => "Idle"
      case Some(request) => s"InFlight#${request.id}"

  private def eventLabel(event: Event): String =
    event match
      case Event.SourceEdited(_, selectedFormat) =>
        s"SourceEdited(format=$selectedFormat)"
      case Event.GraphEdited(_) =>
        "GraphEdited"
      case Event.FormatChanged(newFormat) =>
        s"FormatChanged(new=$newFormat)"
      case Event.ParseCompleted(request, result, selectedFormat) =>
        val resultLabel = if result.isRight then "success" else "failure"
        s"ParseCompleted(id=${request.id}, result=$resultLabel, selected=$selectedFormat, requestFormat=${request.format})"

  private def effectLabel(effect: Effect): String =
    effect match
      case Effect.StartParse(request) => s"StartParse#${request.id}:${request.format}"
      case Effect.SetEditorError(None) => "SetEditorError(None)"
      case Effect.SetEditorError(Some(_)) => "SetEditorError(Some)"

end InternalPhasesMachine
