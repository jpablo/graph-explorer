package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.{EventStream, Signal}
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.backends.{DiagramBackend, DiagramFormat}
import org.jpablo.graphexplorer.viewer.backends.graphviz.{Graphviz, GraphvizBackend}
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.SimpleGraph
import org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidBackend
import org.jpablo.graphexplorer.viewer.domUtils.parseSVG
import org.jpablo.graphexplorer.viewer.components.selection.{GraphvizSelectionStrategy, MermaidSelectionStrategy, SelectableElementStrategy}
import org.jpablo.graphexplorer.viewer.formats.dot.DotText
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph.viewerGraphToText
import org.jpablo.graphexplorer.viewer.backends.mermaid.viewerGraphToMermaidText
import org.jpablo.graphexplorer.viewer.logging.*
import org.jpablo.graphexplorer.viewer.models.ElementIds
import org.jpablo.graphexplorer.viewer.utils.ChangeOrigin
import org.jpablo.graphexplorer.viewer.telemetry.Telemetry
import org.scalajs.dom.svg.SVG

import scala.concurrent.ExecutionContext

// Unified state containing both representations
case class GraphState(
    text:        String,
    viewerGraph: ViewerGraph,
    format:      DiagramFormat,
    lastOrigin:  ChangeOrigin
) derives CanEqual

class InternalPhases(
    graphviz:      Graphviz,
    initialSource: Option[String] = None,
    hiddenNodes:   Signal[HiddenElements],
    resetView:     () => Unit = () => (),
    autoFit:       () => Boolean = () => false,
    editorError:   Var[Option[String]] = Var(None),
    backendFor:    Option[DiagramFormat => DiagramBackend] = None,
    serializeGraph: (ViewerGraph, DiagramFormat) => String = InternalPhases.defaultSerializeGraph,
    val logLevel:  Level = Level.None
)(using Owner, ExecutionContext):

  simpleLog(s"InternalPhases: Initializing with $initialSource", logLevel)
  private val resolveBackend = backendFor.getOrElse(InternalPhases.defaultBackendFor(graphviz))

  // Initialize with the provided source or a minimal graph
  private val initialText   = initialSource.getOrElse("""digraph "G" {}""")
  private val initialFormat = DiagramFormat.detect(initialText)
  val formatSelection       = Var(initialFormat)

  // Start with minimal graph; async parsing will populate it
  private val initialViewerGraph: ViewerGraph = ViewerGraph.minimalWithDirected

  private val initialTransition = InternalPhasesMachine.initialize(
    initialText = initialText,
    initialFormat = initialFormat,
    initialGraph = initialViewerGraph,
    initialOrigin = ChangeOrigin.CodeMirror
  )

  // Single unified state
  private val state = Var(initialTransition.state.snapshot)

  private val inputBus = EventBus[InternalPhasesMachine.MachineInput]()

  private val transitions =
    inputBus.events.scanLeft(initialTransition): (previousTransition, input) =>
      val before = previousTransition.state
      val next = InternalPhasesMachine.step(
        state = before,
        input = input,
        serializeGraph = serializeGraph
      )

      input match
        case InternalPhasesMachine.MachineInput.Ui(event) =>
          simpleLog(InternalPhasesMachine.describeTransition(before, event, next), logLevel)
        case InternalPhasesMachine.MachineInput.Parse(event) =>
          before match
            case inFlight: InternalPhasesMachine.State.InFlight =>
              simpleLog(InternalPhasesMachine.describeTransition(inFlight, event, next), logLevel)
            case _: InternalPhasesMachine.State.Idle =>
              simpleLog("transition event=parse-while-idle parse=Idle->Idle snapshotChanged=false effects=[]", logLevel)
      next

  // Bus for text changes that need async parsing
  private val textChangeBus = EventBus[InternalPhasesMachine.InFlightRequest]()

  private def dispatchUiEvent(event: InternalPhasesMachine.UiEvent): GraphState =
    inputBus.writer.onNext(InternalPhasesMachine.MachineInput.Ui(event))
    state.now()

  private def runEffects(effects: List[InternalPhasesMachine.Effect]): Unit =
    effects.foreach {
      case InternalPhasesMachine.Effect.StartParse(request) =>
        simpleLog(s"[${request.format.displayName}] parseTextToGraphAsync len=${request.text.length} origin=${request.origin}", logLevel)
        textChangeBus.writer.onNext(request)
      case InternalPhasesMachine.Effect.SetEditorError(error) =>
        editorError.set(error)
    }

  // Handle async parsing results
  textChangeBus.events
    .flatMapSwitch: request =>
      EventStream.fromFuture {
        resolveBackend(request.format).textToGraph(request.text)
          .map(graph => request -> Right(graph))
          .recover { case f => request -> Left(f) }
      }
    .foreach: (request, result) =>
      val parseEvent = result match
        case Right(graph) =>
          InternalPhasesMachine.ParseEvent.ParseSucceeded(
            request = request,
            graph = graph,
            selectedFormat = formatSelection.now()
          )
        case Left(f) =>
          InternalPhasesMachine.ParseEvent.ParseFailed(
            request = request,
            error = f,
            selectedFormat = formatSelection.now()
          )
      inputBus.writer.onNext(InternalPhasesMachine.MachineInput.Parse(parseEvent))

  transitions.changes.foreach: transition =>
    runEffects(transition.effects)
    val nextSnapshot = transition.state.snapshot
    if nextSnapshot != state.now() then
      state.set(nextSnapshot)

  // Trigger initial async parsing (must be after subscription is set up)
  runEffects(initialTransition.effects)

  val sourceTextS: Signal[String] =
    state.signal.map(_.text).distinct

  val sourceTextWriter: Observer[String] = Observer[String]: newText =>
    dispatchUiEvent(
      InternalPhasesMachine.UiEvent.SourceEdited(
        newText = newText,
        selectedFormat = formatSelection.now()
      )
    )
    ()

  val fullGraphS: Signal[ViewerGraph] =
    state.signal.map(_.viewerGraph).distinct

  val fullGraphWriter: Observer[ViewerGraph] = Observer[ViewerGraph]: newGraph =>
    dispatchUiEvent(InternalPhasesMachine.UiEvent.GraphEdited(newGraph = newGraph))
    ()

  def setFullGraph(newGraph: ViewerGraph): Unit =
    fullGraphWriter.onNext(newGraph)

  def updateFullGraph(f: ViewerGraph => ViewerGraph): Unit =
    setFullGraph(f(state.now().viewerGraph))

  // Public interface: sourceText as a Var that delegates to the unified state
  val sourceText: Var[String] =
    state.zoomLazy((currentState: GraphState) =>
      withLog("1b. [sourceText <- GraphState]", level = logLevel) {
        // GraphState is the source of truth; update unconditionally
        // Used to update CodeMirror.
        currentState.text
      }
    )((_: GraphState, newText: String) =>
      withLog("1a. [sourceText -> GraphState]", level = logLevel) {
        sourceTextWriter.onNext(newText)
        state.now()
      }
    ).distinct // TODO: consider using distinctByRef

  // Public interface: fullGraphV as a Var that delegates to the unified state
  val fullGraphV: Var[ViewerGraph] =
    state.zoomLazy((currentState: GraphState) =>
      withLog("2a. [GraphState -> fullGraphV: ViewerGraph]", level = logLevel) {
        // GraphState is the source of truth; update unconditionally
        currentState.viewerGraph
      }
    )((_: GraphState, newGraph: ViewerGraph) =>
      withLog("2b. [GraphState <- fullGraphV: ViewerGraph]", level = logLevel) {
        fullGraphWriter.onNext(newGraph)
        state.now()
      }
    ).distinct // TODO: consider using distinctByRef

  val fullGraph = fullGraphS

  val simpleGraph: Signal[SimpleGraph] =
    state.signal.flatMapSwitch { currentState =>
      Signal.fromTry(graphviz.textToSimpleGraph(currentState.text))
    }

  // -------------------------------
  // fullGraphV --> visibleGraph
  // -------------------------------

  /** Graph with hidden nodes removed: ViewerGraph ~> ViewerGraph
    */
  val visibleGraph: Signal[ViewerGraph] =
    fullGraphV.signal.combineWithFn(hiddenNodes): (fullGraph: ViewerGraph, hiddenNodes) =>
      withLog("3. [fullGraphV -> visibleGraph]", level = logLevel) {
        fullGraph.toVisibleGraph(hiddenNodes)
      }
    .distinct
      .tapEach(_ => if autoFit() then resetView())

  // -------------------------------
  // rendering:
  // visibleGraph -> visibleDOT
  // -------------------------------
  val visibleDOT: Signal[DotText] =
    visibleGraph.map { graph =>
      withLog("4. [visibleGraph -> visibleDOT]", level = logLevel) {
        DotText(viewerGraphToText(graph, omitInternal = false))
      }
    }

  /** Signal for the current diagram format. */
  val currentFormat: Signal[DiagramFormat] =
    state.signal.map(_.format).distinct

  /** Signal for the selection strategy based on current format. */
  val selectionStrategy: Signal[SelectableElementStrategy] =
    currentFormat.map:
      case DiagramFormat.DOT     => GraphvizSelectionStrategy
      case DiagramFormat.Mermaid => MermaidSelectionStrategy

  // Re-parse the current text when the user switches the selected format.
  formatSelection.signal.changes.foreach: newFormat =>
    dispatchUiEvent(InternalPhasesMachine.UiEvent.FormatChanged(newFormat))

end InternalPhases

object InternalPhases:
  def defaultBackendFor(graphviz: Graphviz)(using ExecutionContext): DiagramFormat => DiagramBackend =
    val graphvizBackend = GraphvizBackend(graphviz)
    lazy val mermaidBackend = MermaidBackend()
    format =>
      format match
        case DiagramFormat.DOT     => graphvizBackend
        case DiagramFormat.Mermaid => mermaidBackend

  def defaultSerializeGraph(graph: ViewerGraph, format: DiagramFormat): String =
    format match
      case DiagramFormat.DOT     => viewerGraphToText(graph, omitInternal = true)
      case DiagramFormat.Mermaid => viewerGraphToMermaidText(graph)

  /** Process diagram text (DOT or Mermaid) and return an SVG element.
    * Used for generating thumbnails on the library page.
    */
  def processDotText(
      graphviz:          Graphviz,
      dot:              DotText,
      telemetryContext: Seq[(String, Any)] = Nil
  )(using ExecutionContext): Signal[ReactiveSvgElement[SVG]] =
    val format = DiagramFormat.detect(dot.value)
    ThumbnailSvgCache.get(format, dot.value) match
      case Some(proto) =>
        Telemetry.log(
          "thumb.cache.hit",
          (telemetryContext ++ Seq(
            "format"    -> format.toString,
            "cacheSize" -> ThumbnailSvgCache.size
          ))*
        )
        Signal.fromValue(ThumbnailSvgCache.cloneSvg(proto))

      case None =>
        Telemetry.log(
          "thumb.cache.miss",
          (telemetryContext ++ Seq(
            "format"    -> format.toString,
            "cacheSize" -> ThumbnailSvgCache.size
          ))*
        )

        format match
          case DiagramFormat.DOT =>
            // DOT format - use Graphviz (synchronous)
            val startedAt = Telemetry.nowMs()

            val sgStartedAt     = Telemetry.nowMs()
            val simpleGraphTry  = graphviz.textToSimpleGraph(dot.value)
            Telemetry.log(
              "thumb.dot.textToSimpleGraph",
              (telemetryContext ++ Seq(
                "dtMs" -> (Telemetry.nowMs() - sgStartedAt),
                "ok"   -> simpleGraphTry.isSuccess
              ))*
            )

            val resultTry =
              for
                simpleGraph <- simpleGraphTry
                viewerGraph = simplegraph.toViewerGraph(simpleGraph).toVisibleGraph(ElementIds())
                dotText0    = viewerGraphToText(viewerGraph, omitInternal = false)
                svgStartedAt = Telemetry.nowMs()
                svgTry       = graphviz.textToSvg(DotText(dotText0))
                _ = Telemetry.log(
                  "thumb.dot.textToSvg",
                  (telemetryContext ++ Seq(
                    "dtMs" -> (Telemetry.nowMs() - svgStartedAt),
                    "ok"   -> svgTry.isSuccess
                  ))*
                )
                svg <- svgTry
              yield svg.svg.ref

            Telemetry.log(
              "thumb.dot.total",
              (telemetryContext ++ Seq(
                "dtMs" -> (Telemetry.nowMs() - startedAt),
                "ok"   -> resultTry.isSuccess
              ))*
            )

            resultTry.foreach: proto =>
              ThumbnailSvgCache.put(format, dot.value, proto)
              Telemetry.log(
                "thumb.cache.store",
                (telemetryContext ++ Seq(
                  "format"    -> format.toString,
                  "cacheSize" -> ThumbnailSvgCache.size
                ))*
              )

            Signal.fromTry(resultTry).map(ThumbnailSvgCache.cloneSvg)

          case DiagramFormat.Mermaid =>
            // Mermaid format - use MermaidBackend (asynchronous)
            // Render to a string, then parse into a fresh element so SPA re-mounts don't reuse DOM nodes
            val startedAt = Telemetry.nowMs()
            Telemetry.log(
              "thumb.mermaid.start",
              (telemetryContext ++ Seq("sourceChars" -> dot.value.length))*
            )
            val backend = MermaidBackend()
            Signal
              .fromFuture(backend.textToSvg(dot.value).map(_.svg.ref.outerHTML): scala.concurrent.Future[String])
              .map: (svgHtmlOpt: Option[String]) =>
                svgHtmlOpt match
                  case Some(svgHtml) =>
                    Telemetry.log(
                      "thumb.mermaid.done",
                      (telemetryContext ++ Seq(
                        "dtMs" -> (Telemetry.nowMs() - startedAt),
                        "ok"   -> true
                      ))*
                    )
                    val proto = parseSVG(svgHtml).ref
                    ThumbnailSvgCache.put(format, dot.value, proto)
                    Telemetry.log(
                      "thumb.cache.store",
                      (telemetryContext ++ Seq(
                        "format"    -> format.toString,
                        "cacheSize" -> ThumbnailSvgCache.size
                      ))*
                    )
                    ThumbnailSvgCache.cloneSvg(proto)
                  case None =>
                    emptySvg

  /** Empty SVG placeholder for when rendering fails */
  private def emptySvg: ReactiveSvgElement[SVG] =
    import com.raquo.laminar.api.L.svg.*
    svg(
      width  := "100",
      height := "100",
      text(
        x          := "50",
        y          := "50",
        textAnchor := "middle",
        "No preview"
      )
    )

end InternalPhases
