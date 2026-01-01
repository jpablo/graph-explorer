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
import org.jpablo.graphexplorer.viewer.logging.*
import org.jpablo.graphexplorer.viewer.models.ElementIds
import org.jpablo.graphexplorer.viewer.utils.ChangeOrigin
import org.jpablo.graphexplorer.viewer.telemetry.Telemetry
import org.scalajs.dom.svg.SVG

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

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
    val logLevel:  Level = Level.None
)(using Owner, ExecutionContext):

  simpleLog(s"InternalPhases: Initializing with $initialSource", logLevel)

  // Backends for different diagram formats
  private val graphvizBackend = GraphvizBackend(graphviz)
  private lazy val mermaidBackend = MermaidBackend()

  private def backendFor(format: DiagramFormat): DiagramBackend = format match
    case DiagramFormat.DOT     => graphvizBackend
    case DiagramFormat.Mermaid => mermaidBackend

  // Initialize with the provided source or a minimal graph
  private val initialText   = initialSource.getOrElse("""digraph "G" {}""")
  private val initialFormat = DiagramFormat.detect(initialText)
  val formatSelection       = Var(initialFormat)

  // Parse initial text synchronously for DOT, async for Mermaid
  private val initialViewerGraph: ViewerGraph =
    if initialFormat == DiagramFormat.DOT then
      // DOT is synchronous via Graphviz
      graphviz.textToSimpleGraph(initialText) match
        case Success(sg) => simplegraph.toViewerGraph(sg)
        case Failure(_)  => ViewerGraph.minimalWithDirected
    else
      // Mermaid is async - start with minimal, update later
      ViewerGraph.minimalWithDirected

  // Single unified state
  private val state = Var(
    GraphState(
      text = initialText,
      viewerGraph = initialViewerGraph,
      format = initialFormat,
      lastOrigin = ChangeOrigin.CodeMirror
    )
  )

  // Bus for text changes that need async parsing (Mermaid only)
  private val textChangeBus = EventBus[(String, DiagramFormat, ChangeOrigin)]()

  // Trigger initial async parsing for Mermaid
  if initialFormat == DiagramFormat.Mermaid then
    parseTextToGraphAsync(initialText, initialFormat, ChangeOrigin.CodeMirror)

  // Handle async parsing results (primarily for Mermaid)
  textChangeBus.events
    .flatMapSwitch { case (text, format, origin) =>
      EventStream.fromFuture(
        backendFor(format).textToGraph(text).transform {
          case Success(graph) =>
            editorError.set(None)
            Success((text, graph, format, origin))
          case Failure(f) =>
            dom.console.error(s"Error parsing ${format.displayName} to ViewerGraph: ${f.getMessage}")
            editorError.set(Option(f.getMessage))
            Success((text, ViewerGraph.minimalWithDirected, format, origin))
        }
      )
    }
    .foreach { case (text, graph, format, origin) =>
      // Only update if text and selected format haven't changed since we started parsing
      if state.now().text == text && formatSelection.now() == format then
        state.set(GraphState(text, graph, format, origin))
    }

  // Public interface: sourceText as a Var that delegates to the unified state
  val sourceText: Var[String] =
    state.zoomLazy((currentState: GraphState) =>
      withLog("1b. [sourceText <- GraphState]", level = logLevel) {
        // GraphState is the source of truth; update unconditionally
        // Used to update CodeMirror.
        currentState.text
      }
    )((currentState: GraphState, newText: String) =>
      withLog("1a. [sourceText -> GraphState]", level = logLevel) {
        // New source of truth: incoming text
        if newText != currentState.text then
          val selectedFormat = formatSelection.now()
          buildGraphStateFromText(
            currentState = currentState,
            newText = newText,
            format = selectedFormat,
            origin = ChangeOrigin.CodeMirror
          )
        else
          currentState
      }
    ).distinct // TODO: consider using distinctByRef

  // Public interface: fullGraphV as a Var that delegates to the unified state
  val fullGraphV: Var[ViewerGraph] =
    state.zoomLazy((currentState: GraphState) =>
      withLog("2a. [GraphState -> fullGraphV: ViewerGraph]", level = logLevel) {
        // GraphState is the source of truth; update unconditionally
        currentState.viewerGraph
      }
    )((currentState: GraphState, newGraph: ViewerGraph) =>
      withLog("2b. [GraphState <- fullGraphV: ViewerGraph]", level = logLevel) {
        // New source of truth: incoming graph
        // Note: This keeps the current format since the graph was modified in-place
        if newGraph != currentState.viewerGraph then
          GraphState(
            text = viewerGraphToText(newGraph, omitInternal = true),
            viewerGraph = newGraph,
            format = currentState.format, // Preserve format when graph is edited
            lastOrigin = ChangeOrigin.Graph
          )
        else
          currentState
      }
    ).distinct // TODO: consider using distinctByRef

  val fullGraph = fullGraphV.signal.distinct

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

  /** Triggers async parsing of text into a ViewerGraph. */
  private def parseTextToGraphAsync(text: String, format: DiagramFormat, origin: ChangeOrigin): Unit =
    if text.trim.nonEmpty then
      textChangeBus.writer.onNext((text, format, origin))

  /** Build a new GraphState based on the provided text and format. */
  private def buildGraphStateFromText(
      currentState: GraphState,
      newText:      String,
      format:       DiagramFormat,
      origin:       ChangeOrigin
  ): GraphState =
    format match
      case DiagramFormat.DOT =>
        graphviz.textToSimpleGraph(newText) match
          case Success(sg) =>
            editorError.set(None)
            GraphState(
              text = newText,
              viewerGraph = simplegraph.toViewerGraph(sg),
              format = format,
              lastOrigin = origin
            )
          case Failure(f) =>
            editorError.set(Option(f.getMessage))
            currentState.copy(text = newText, format = format, lastOrigin = origin)
      case DiagramFormat.Mermaid =>
        parseTextToGraphAsync(newText, format, origin)
        currentState.copy(text = newText, format = format, lastOrigin = origin)

  // Re-parse the current text when the user switches the selected format.
  formatSelection.signal.changes.foreach: newFormat =>
    val currentState = state.now()
    if currentState.format != newFormat then
      state.set(
        buildGraphStateFromText(
          currentState = currentState,
          newText = currentState.text,
          format = newFormat,
          origin = currentState.lastOrigin
        )
      )

end InternalPhases

object InternalPhases:
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
