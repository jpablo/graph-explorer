package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.typings.SimpleGraphConverter
import org.jpablo.graphexplorer.viewer.formats.dot.DotText
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.GraphType
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.logging.*
import org.jpablo.graphexplorer.viewer.utils.ChangeOrigin
import org.scalajs.dom.svg.SVG

import scala.util.{Failure, Success}

case class Versioned[A](value: A, origin: ChangeOrigin)

def synchronize[S, T](
    source:  Var[S],
    target:  Var[T],
    labelT:  String = "",
    toT:     (S, T) => T,
    updateT: (S, T, T) => Boolean,
    labelS:  String = "",
    toS:     (S, T) => S,
    updateS: (S, T, S) => Boolean,
    level:   Level = Level.None
)(using Owner): Unit =
  // source -> target
  for s <- source.signal do
    val t  = target.now()
    val t1 = toT(s, t)
    if updateT(s, t, t1) then
      withLog(labelT, level = level)({ target.set(t1); t1 })
    else if level != Level.None then
      dom.console.info(s"Skipping update: $labelT, source: $s, target: $t")
  // target -> source
  for t <- target.signal do
    val s  = source.now()
    val s1 = toS(s, t)
    if updateS(s, t, s1) then
      withLog(labelS, level = level)({ source.set(s1); s1 })
    else if level != Level.None then
      dom.console.info(s"Skipping update: $labelS, source: $s, target: $t")
end synchronize

class InternalPhases(
    graphviz:      Graphviz,
    initialSource: Option[String] = None,
    hiddenNodes:   Signal[HiddenElements],
    resetView:     () => Unit = () => (),
    autoFit:       () => Boolean = () => false,
    editorError:   Var[Option[String]] = Var(None)
)(using Owner):

  val logLevel = Level.None

  // three types of Vars:
  // (a) updated outside InternalPhases (either by CodeMirror or the UI)
  // (b) updates linked to a var of type (a)
  // (c) updates coming from both directions

  // updated by CodeMirror (a)
  simpleLog(s"InternalPhases: Initializing with $initialSource", logLevel)

  val sourceText: Var[String] = Var(initialSource.getOrElse("""digraph "G" {}"""))

  // Note: It is critical that the initial values below are the same. Otherwise, superfluous updates will be triggered.

  // (b)
  private val versionedText = Var(Versioned(sourceText.now(), ChangeOrigin.CodeMirror))

  // (b)
  private val versionedFullGraphV = Var(dotToVersionedGraph(versionedText.now()))

  // updated by the UI (a)
  val fullGraphV: Var[ViewerGraph] = Var(versionedFullGraphV.now().value)

  val fullGraph = fullGraphV.signal.distinct

  // -------------------------------
  // sourceText <-> versionedText
  // -------------------------------
  synchronize[String, Versioned[String]](
    source = sourceText,
    target = versionedText,
    // -------------------------------
    labelT = "1a. [sourceText -> versionedText]", // a -> b
    toT = (st, _) => Versioned[String](st, ChangeOrigin.CodeMirror),
    updateT = (st, vt, _) => st != vt.value,
    // -------------------------------
    labelS = "1b. [sourceText <- versionedText]", // b -> a
    toS = (_, vt) => vt.value,
    updateS = (st, vt, _) => st != vt.value,
    level = logLevel
  )

  // -------------------------------
  // versionedText <-> versionedFullGraphV
  // -------------------------------
  synchronize[Versioned[String], Versioned[ViewerGraph]](
    source = versionedText,
    target = versionedFullGraphV,
    // -------------------------------
    labelT = "2a. [versionedText -> versionedFullGraphV]", // b -> c
    toT = (vt, _) => dotToVersionedGraph(vt),
    updateT = (_, vg, vg1) => vg.value != vg1.value && vg1.origin == ChangeOrigin.CodeMirror,
    // -------------------------------
    labelS = "2b. [versionedText <- versionedFullGraphV]", // c -> b
    toS = { (_, vg) =>
      val graph     = SimpleGraphConverter.fromViewerGraphElements(vg.value.elements.combineStyleAttributes)
      val dotString = SimpleGraphConverter.graphToDotString(graph, omitInternal = true)
      Versioned[String](dotString, vg.origin)
    },
    updateS = (vt, ast, vt1) => vt1.value != vt.value && ast.origin == ChangeOrigin.Graph,
    level = logLevel
  )

  // -------------------------------
  // versionedFullGraphV <-> fullGraphV
  // -------------------------------
  synchronize[Versioned[ViewerGraph], ViewerGraph](
    source = versionedFullGraphV,
    target = fullGraphV,
    // -------------------------------
    labelT = "3a. [versionedFullGraphV -> fullGraphV]", // b -> a
    toT = (vg, _) => vg.value,
    updateT = (_, g, g1) => g != g1,
    // -------------------------------
    labelS = "3b. [versionedFullGraphV <- fullGraphV]", // a -> b
    toS = (vg, g) => Versioned[ViewerGraph](g, ChangeOrigin.Graph),
    updateS = (vg, g, _) => vg.value != g,
    level = logLevel
  )

  // -------------------------------
  // fullGraphV --> visibleGraph
  // -------------------------------

  /** Graph with hidden nodes removed: ViewerGraph ~> ViewerGraph
    */
  val visibleGraph: Signal[ViewerGraph] =
    fullGraphV.signal.combineWithFn(hiddenNodes): (fullGraph: ViewerGraph, hiddenNodes) =>
      withLog("4. [fullGraphV -> visibleGraph]", level = logLevel) {
        fullGraph
          .removeUnsupportedFeatures
          .removeElements(hiddenNodes)
          .withDefaultTheme
      }
    .distinct
      .tapEach(_ => if autoFit() then resetView())

  // -------------------------------
  // rendering:
  // visibleGraph -> visibleDOT
  // -------------------------------
  val visibleDOT: Signal[DotText] =
    visibleGraph.map { graph =>
      withLog("5. [visibleGraph -> visibleDOT]", level = logLevel) {
        // Note: `viewerGraphElementsToDotString` discards default attributes.
        DotText(SimpleGraphConverter.viewerGraphElementsToDotString(graph.elements.combineStyleAttributes))
      }
    }

  /** Converts a versioned DOT graph string into a versioned `ViewerGraph` representation. If the provided DOT string is empty or contains
    * only whitespace, it defaults to a minimal directed graph. If parsing the DOT string fails, it logs the error and returns a minimal
    * directed graph as fallback.
    *
    * @param versionedText
    *   A `Versioned[String]` instance containing the DOT graph string, version information, and origin metadata.
    */
  private def dotToVersionedGraph(versionedText: Versioned[String]) = {
    val Versioned(dotText, origin) = versionedText
    // Safety check: don't process empty or whitespace-only strings
    if dotText.trim.isEmpty then
      Versioned(ViewerGraph.minimalWithDirected, origin)
    else
      graphviz.renderToJsonGraph(dotText) match
        case Success(graph) =>
          editorError.set(None)
          val elements    = SimpleGraphConverter.toViewerGraphElements(graph)
          val graphTpe    = if graph.directed then GraphType.digraph else GraphType.graph
          val viewerGraph = ViewerGraph(elements.expandStyleAttributes, id = graph.name, tpe = graphTpe)
          Versioned[ViewerGraph](viewerGraph, origin)

        case Failure(f) =>
          dom.console.error(s"Error parsing DotText to ViewerGraph: ${f.getMessage}")
          editorError.set(Option(f.getMessage))
          Versioned(ViewerGraph.minimalWithDirected, ChangeOrigin.CodeMirror)
  }

end InternalPhases

object InternalPhases:

  def processDotText(graphviz: Graphviz, dot: DotText): Signal[ReactiveSvgElement[SVG]] =
    Signal.fromTry:
      for
        graph <- graphviz.renderToJsonGraph(dot.value)
        dotText0 = SimpleGraphConverter.graphToDotString(graph)
        svg <- graphviz.renderToSvg(DotText(dotText0))
      yield svg.svg

end InternalPhases
