package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.{EventStream, Signal}
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.backends.{DiagramBackend, DiagramFormat}
import org.jpablo.graphexplorer.viewer.backends.graphviz.{Graphviz, GraphvizBackend}
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.SimpleGraph
import org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidBackend
import org.jpablo.graphexplorer.viewer.domUtils.parseSVG
import org.jpablo.graphexplorer.viewer.components.selection.{GraphvizSelectionStrategy, MermaidSelectionStrategy, SelectableElementStrategy}
import org.jpablo.graphexplorer.viewer.formats.dot.DotText
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph.viewerGraphToText
import org.jpablo.graphexplorer.viewer.backends.mermaid.viewerGraphToMermaidText
import org.jpablo.graphexplorer.viewer.logging.*
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

  // Start with minimal graph; async parsing will populate it
  private val initialViewerGraph: ViewerGraph = ViewerGraph.minimalWithDirected

  // Single unified state
  private val state = Var(
    GraphState(
      text = initialText,
      viewerGraph = initialViewerGraph,
      format = initialFormat,
      lastOrigin = ChangeOrigin.CodeMirror
    )
  )

  // Bus for text changes that need async parsing
  private val textChangeBus = EventBus[(String, DiagramFormat, ChangeOrigin)]()

  // Handle async parsing results
  textChangeBus.events
    .flatMapSwitch { case (text, format, origin) =>
      val parseFuture =
        backendFor(format).textToGraph(text).transform {
          case Success(graph) =>
            editorError.set(None)
            Success((text, graph, format, origin))
          case Failure(f) =>
            simpleLog(s"Error parsing ${format.displayName} to ViewerGraph: ${f.getMessage}", logLevel)
            editorError.set(Option(f.getMessage))
            Success((text, ViewerGraph.minimalWithDirected, format, origin))
        }
      EventStream.fromFuture(parseFuture)
    }
    .foreach { case (text, graph, format, origin) =>
      // Only update if text and selected format haven't changed since we started parsing
      if state.now().text == text && formatSelection.now() == format then
        simpleLog(
          s"[${format.displayName}] viewerGraph nodes=${graph.nodeIds.size} arrows=${graph.arrowIds.size} groups=${graph.groupIds.size} origin=$origin",
          logLevel
        )
        state.set(GraphState(text, graph, format, origin))
    }

  // Trigger initial async parsing (must be after subscription is set up)
  parseTextToGraphAsync(initialText, initialFormat, ChangeOrigin.CodeMirror)

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
          parseTextToGraphAsync(newText, selectedFormat, ChangeOrigin.CodeMirror)
          currentState.copy(text = newText, format = selectedFormat, lastOrigin = ChangeOrigin.CodeMirror)
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
          val serializedText = currentState.format match
            case DiagramFormat.DOT     => viewerGraphToText(newGraph, omitInternal = true)
            case DiagramFormat.Mermaid => viewerGraphToMermaidText(newGraph)
          GraphState(
            text = serializedText,
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
      simpleLog(s"[${format.displayName}] parseTextToGraphAsync len=${text.length} origin=$origin", logLevel)
      textChangeBus.writer.onNext((text, format, origin))

  // Re-parse the current text when the user switches the selected format.
  formatSelection.signal.changes.foreach: newFormat =>
    val currentState = state.now()
    if currentState.format != newFormat then
      state.set(currentState.copy(format = newFormat))
      parseTextToGraphAsync(currentState.text, newFormat, currentState.lastOrigin)

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
            // ONE svg-only render (`textToSvgOnly`) straight from the source
            // text. The old path laid the graph out TWICE — textToSimpleGraph
            // (full layout → dot_json → JSON parse), a ViewerGraph round-trip
            // re-serialized to DOT, then textToSvg (full layout again + json0
            // + edge positions a static thumbnail never reads). The 0ms delay
            // moves each card's render into its own macrotask so the browser
            // paints between cards instead of freezing on the whole batch.
            val startedAt = Telemetry.nowMs()
            EventStream
              .delay(0, dot)
              .map: _ =>
                val svgStartedAt = Telemetry.nowMs()
                val resultTry    = graphviz.textToSvgOnly(dot)
                Telemetry.log(
                  "thumb.dot.textToSvg",
                  (telemetryContext ++ Seq(
                    "dtMs" -> (Telemetry.nowMs() - svgStartedAt),
                    "ok"   -> resultTry.isSuccess
                  ))*
                )
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
                ThumbnailSvgCache.cloneSvg(resultTry.get) // failure → error channel, as Signal.fromTry did
              .toSignal(svg.svg()) // empty-svg placeholder until the deferred render lands

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
