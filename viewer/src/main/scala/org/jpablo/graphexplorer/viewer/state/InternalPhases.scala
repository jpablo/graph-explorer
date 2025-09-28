package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.SimpleGraph
import org.jpablo.graphexplorer.viewer.formats.dot.DotText
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph.viewerGraphToText
import org.jpablo.graphexplorer.viewer.logging.*
import org.jpablo.graphexplorer.viewer.models.ElementIds
import org.jpablo.graphexplorer.viewer.utils.ChangeOrigin
import org.scalajs.dom.svg.SVG

import scala.util.{Failure, Success}

// Unified state containing both representations
case class GraphState(
    text:        String,
    viewerGraph: ViewerGraph,
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
)(using Owner):

  simpleLog(s"InternalPhases: Initializing with $initialSource", logLevel)

  // Initialize with the provided source or a minimal graph
  private val initialText  = initialSource.getOrElse("""digraph "G" {}""")
  private val initialViewerGraph: ViewerGraph = parseTextToGraph(initialText)

  // Single unified state
  private val state = Var(
    GraphState(
      text = initialText,
      viewerGraph = initialViewerGraph,
      lastOrigin = ChangeOrigin.CodeMirror
    )
  )

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
          GraphState(
            text = newText,
            viewerGraph = parseTextToGraph(newText),
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
        if newGraph != currentState.viewerGraph then
          GraphState(
            text = viewerGraphToText(newGraph, omitInternal = true),
            viewerGraph = newGraph,
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

  /** Parses DOT text into a ViewerGraph. Returns a minimal graph on error.
    */
  private def parseTextToGraph(dotText: String): ViewerGraph =
    // Safety check: don't process empty or whitespace-only strings
    if dotText.trim.isEmpty then
      ViewerGraph.minimalWithDirected
    else
      graphviz.textToSimpleGraph(dotText) match
        case Success(simpleGraph) =>
          editorError.set(None)
          // simpleGraph has no node/arrow defaults! (all attributes are "flattened")
          simplegraph.toViewerGraph(simpleGraph)

        case Failure(f) =>
          dom.console.error(s"Error parsing DotText to ViewerGraph: ${f.getMessage}")
          editorError.set(Option(f.getMessage))
          ViewerGraph.minimalWithDirected

end InternalPhases

object InternalPhases:

  def processDotText(graphviz: Graphviz, dot: DotText): Signal[ReactiveSvgElement[SVG]] =
    Signal.fromTry:
      for
        simpleGraph <- graphviz.textToSimpleGraph(dot.value)
        viewerGraph = simplegraph.toViewerGraph(simpleGraph).toVisibleGraph(ElementIds())
        dotText0    = viewerGraphToText(viewerGraph, omitInternal = false)
        svg <- graphviz.textToSvg(DotText(dotText0))
      yield svg.svg

end InternalPhases
