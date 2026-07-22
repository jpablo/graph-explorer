package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.{EventStream, Signal}
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.backends.{DiagramFormat, DiagramLanguages}
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElementStrategy
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.logging.*
import org.jpablo.graphexplorer.viewer.utils.ChangeOrigin

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

// Unified state containing both representations
case class GraphState(
    text:        String,
    viewerGraph: ViewerGraph,
    format:      DiagramFormat,
    lastOrigin:  ChangeOrigin,
    // False when `viewerGraph` does NOT correspond to `text` (the parse failed). While out of
    // sync, graph edits are ignored: serializing the placeholder graph would overwrite the
    // user's document in the editor AND localStorage (observed data-loss path).
    graphInSync: Boolean = true
) derives CanEqual

/** Reactive text <-> graph synchronization engine.
  *
  * This component is backend-agnostic: all format-specific behavior (parsing, serialization, selection
  * strategy, format detection) is injected through the [[DiagramLanguages]] registry. `DiagramFormat`
  * is kept only as an equatable identity tag inside [[GraphState]]; the corresponding behavior is
  * resolved on demand via `languages.forFormat(format)`.
  */
class InternalPhases(
    languages:     DiagramLanguages,
    initialSource: Option[String] = None,
    hiddenNodes:   Signal[HiddenElements],
    resetView:     () => Unit = () => (),
    autoFit:       () => Boolean = () => false,
    editorError:   Var[Option[String]] = Var(None),
    val logLevel:  Level = Level.None
)(using Owner, ExecutionContext):

  simpleLog(s"InternalPhases: Initializing with $initialSource", logLevel)

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

  // Handle async parsing results.
  // NOTE: all side effects (editorError included) happen in the guarded foreach below, NOT in
  // the Future transform: flatMapSwitch abandons superseded parses, but their Futures still
  // complete — unguarded writes from them used to flash stale errors (or clear real ones).
  textChangeBus.events
    .flatMapSwitch { case (text, format, origin) =>
      if text.trim.isEmpty then
        // An empty document parses to the empty graph (in sync): the canvas clears together
        // with the editor, and a later canvas edit cannot resurrect the deleted content.
        EventStream.fromValue((text, Right(ViewerGraph.minimalWithDirected), format, origin))
      else
        val parseFuture =
          languages.forFormat(format).textToGraph(text).transform {
            case Success(graph) => Success((text, Right(graph), format, origin))
            case Failure(f) =>
              simpleLog(s"Error parsing ${format.displayName} to ViewerGraph: ${f.getMessage}", logLevel)
              Success((text, Left(f.getMessage), format, origin))
          }
        EventStream.fromFuture(parseFuture)
    }
    .foreach { case (text, result, format, origin) =>
      // Only update if text and selected format haven't changed since we started parsing
      if state.now().text == text && formatSelection.now() == format then
        result match
          case Right(graph) =>
            editorError.set(None)
            simpleLog(
              s"[${format.displayName}] viewerGraph nodes=${graph.nodeIds.size} arrows=${graph.arrowIds.size} groups=${graph.groupIds.size} origin=$origin",
              logLevel
            )
            state.set(GraphState(text, graph, format, origin))
          case Left(error) =>
            editorError.set(Option(error))
            // Keep the user's text; show the placeholder graph but mark it OUT OF SYNC so
            // graph edits are ignored until a successful parse (prevents overwriting the
            // document with a serialized near-empty graph).
            state.set(GraphState(text, ViewerGraph.minimalWithDirected, format, origin, graphInSync = false))
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
        if !currentState.graphInSync then
          // The displayed graph does not correspond to the current text (parse failed).
          // Serializing it would overwrite the user's document — ignore the edit.
          simpleLog("Ignoring graph edit while text and graph are out of sync (parse error)", logLevel)
          currentState
        else if newGraph != currentState.viewerGraph then
          val serializedText =
            languages.forFormat(currentState.format).graphToText(newGraph, omitInternal = true)
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

  /** Signal for the current diagram format. */
  val currentFormat: Signal[DiagramFormat] =
    state.signal.map(_.format).distinct

  // -------------------------------
  // rendering:
  // visibleGraph -> visibleText (serialized in the current language)
  // -------------------------------
  /** The visible graph serialized in the currently selected language (DOT text in DOT mode, Mermaid
    * text in Mermaid mode). This is the text the current backend renders.
    */
  val visibleText: Signal[String] =
    visibleGraph.combineWithFn(currentFormat): (graph: ViewerGraph, format: DiagramFormat) =>
      withLog("4. [visibleGraph -> visibleText]", level = logLevel) {
        languages.forFormat(format).graphToText(graph, omitInternal = false)
      }

  /** Signal for the selection strategy based on current format. */
  val selectionStrategy: Signal[SelectableElementStrategy] =
    currentFormat.map(languages.forFormat(_).selectionStrategy)

  /** Triggers async parsing of text into a ViewerGraph. Empty text is handled by the same
    * pipeline (committing the empty graph), so the canvas always tracks the editor.
    */
  private def parseTextToGraphAsync(text: String, format: DiagramFormat, origin: ChangeOrigin): Unit =
    simpleLog(s"[${format.displayName}] parseTextToGraphAsync len=${text.length} origin=$origin", logLevel)
    textChangeBus.writer.onNext((text, format, origin))

  // Re-parse the current text when the user switches the selected format.
  formatSelection.signal.changes.foreach: newFormat =>
    val currentState = state.now()
    if currentState.format != newFormat then
      state.set(currentState.copy(format = newFormat))
      parseTextToGraphAsync(currentState.text, newFormat, currentState.lastOrigin)

end InternalPhases
