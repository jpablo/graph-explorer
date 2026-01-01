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
import org.jpablo.graphexplorer.viewer.components.selection.{GraphvizSelectionStrategy, MermaidSelectionStrategy, SelectableElementStrategy}
import org.jpablo.graphexplorer.viewer.formats.dot.DotText
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph.viewerGraphToText
import org.jpablo.graphexplorer.viewer.logging.*
import org.jpablo.graphexplorer.viewer.models.ElementIds
import org.jpablo.graphexplorer.viewer.utils.ChangeOrigin
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
    parseTextToGraphAsync(initialText, initialFormat)

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
      // Only update if text hasn't changed since we started parsing
      if state.now().text == text then
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
          val newFormat = DiagramFormat.detect(newText)
          val newGraph = newFormat match
            case DiagramFormat.DOT =>
              // DOT is synchronous - parse immediately
              graphviz.textToSimpleGraph(newText) match
                case Success(sg) =>
                  editorError.set(None)
                  simplegraph.toViewerGraph(sg)
                case Failure(f) =>
                  editorError.set(Option(f.getMessage))
                  currentState.viewerGraph
            case DiagramFormat.Mermaid =>
              // Mermaid is async - trigger parsing and keep old graph for now
              textChangeBus.writer.onNext((newText, newFormat, ChangeOrigin.CodeMirror))
              currentState.viewerGraph
          GraphState(
            text = newText,
            viewerGraph = newGraph,
            format = newFormat,
            lastOrigin = ChangeOrigin.CodeMirror
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
  private def parseTextToGraphAsync(text: String, format: DiagramFormat): Unit =
    if text.trim.nonEmpty then
      textChangeBus.writer.onNext((text, format, ChangeOrigin.CodeMirror))

end InternalPhases

object InternalPhases:

  /** Process diagram text (DOT or Mermaid) and return an SVG element.
    * Used for generating thumbnails on the library page.
    */
  def processDotText(graphviz: Graphviz, dot: DotText)(using ExecutionContext): Signal[ReactiveSvgElement[SVG]] =
    val format = DiagramFormat.detect(dot.value)
    format match
      case DiagramFormat.DOT =>
        // DOT format - use Graphviz (synchronous)
        Signal.fromTry:
          for
            simpleGraph <- graphviz.textToSimpleGraph(dot.value)
            viewerGraph = simplegraph.toViewerGraph(simpleGraph).toVisibleGraph(ElementIds())
            dotText0    = viewerGraphToText(viewerGraph, omitInternal = false)
            svg <- graphviz.textToSvg(DotText(dotText0))
          yield svg.svg

      case DiagramFormat.Mermaid =>
        // Mermaid format - use MermaidBackend (asynchronous)
        val mermaidBackend = MermaidBackend()
        Signal
          .fromFuture(mermaidBackend.textToSvg(dot.value).map(_.svg))
          .map(_.getOrElse(emptySvg))

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
