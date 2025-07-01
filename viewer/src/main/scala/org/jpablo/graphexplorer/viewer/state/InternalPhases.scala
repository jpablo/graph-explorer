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
import org.jpablo.graphexplorer.viewer.utils.{ChangeOrigin, Version}
import org.scalajs.dom.svg.SVG

import scala.util.{Failure, Success}

case class Versioned[A](value: A, version: Version, origin: ChangeOrigin)

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
      withLog(labelT, level = level)(target.set(t1))
  // target -> source
  for t <- target.signal do
    val s  = source.now()
    val s1 = toS(s, t)
    if updateS(s, t, s1) then
      withLog(labelS, level = level)(source.set(s1))
end synchronize

class InternalPhases(
    graphviz:      Graphviz,
    initialSource: Option[String] = None,
    hiddenNodes:   Signal[HiddenElements],
    resetView:     () => Unit,
    autoFit:       () => Boolean,
    editorError:   Var[Option[String]]
)(using Owner):

  // three types of Vars:
  // (a) updated outside of SourceFlow (either by CodeMirror or the UI)
  // (b) updates linked to a var of type (a)
  // (c) updates coming from both directions

  // updated by CodeMirror (a)
  val sourceText: Var[String] = Var(initialSource.getOrElse("""digraph "G" {}"""))
  // (b)
  private val versionedText = Var(Versioned(sourceText.now(), 0, ChangeOrigin.CodeMirror))

  // (b)
  private val versionedFullGraphV = Var(Versioned(ViewerGraph.minimal, 0, ChangeOrigin.CodeMirror))

  // updated by the UI (a)
  val fullGraphV: Var[ViewerGraph] = Var(ViewerGraph.minimal)

  val fullGraph = fullGraphV.signal

  // -------------------------------
  // sourceText <-> versionedText
  // -------------------------------
  synchronize[String, Versioned[String]](
    source = sourceText,
    target = versionedText,
    // -------------------------------
    labelT = "[sourceText -> versionedText]", // a -> b
    toT = (st, vt) => Versioned[String](st, vt.version + 1, ChangeOrigin.CodeMirror),
    updateT = (st, vt, vt1) => st != vt.value,
    // -------------------------------
    labelS = "[versionedText -> sourceText]", // b -> a
    toS = (st, vt) => vt.value,
    updateS = (st, vt, st1) => st != vt.value,
    level = Level.None
  )

  // -------------------------------
  // versionedText <-> versionedFullGraphV
  // -------------------------------
  synchronize[Versioned[String], Versioned[ViewerGraph]](
    source = versionedText,
    target = versionedFullGraphV,
    // -------------------------------
    labelT = "[versionedText -> versionedFullGraphV]", // b -> c
    toT = { (vt, _) =>
      // Safety check: don't process empty or whitespace-only strings
      if (vt.value.trim.isEmpty) then {
        Versioned(ViewerGraph.minimal, vt.version, vt.origin)
      } else {

        graphviz.renderToJsonGraph(vt.value) match
          case Success(graph) =>
            editorError.set(None)
            val elements    = SimpleGraphConverter.toViewerGraphElements(graph)
            val graphTpe    = if graph.directed then GraphType.digraph else GraphType.graph
            val viewerGraph = ViewerGraph(elements.expandStyleAttributes, id = graph.name, tpe = graphTpe)
            Versioned[ViewerGraph](viewerGraph, vt.version, vt.origin)

          case Failure(f) =>
            dom.console.error(s"Error parsing DotText to ViewerGraph: ${f.getMessage}")
            editorError.set(Option(f.getMessage))
            Versioned(ViewerGraph.minimal, vt.version, ChangeOrigin.CodeMirror)

      }
    },
    updateT = (vt, ast, ast1) => ast.value != ast1.value && ast1.origin == ChangeOrigin.CodeMirror,
    // -------------------------------
    labelS = "[versionedFullGraphV -> versionedText]", // c -> b
    toS = { (vt, vg: Versioned[ViewerGraph]) =>
      val graph     = SimpleGraphConverter.fromViewerGraphElements(vg.value.elements.combineStyleAttributes)
      val dotString = SimpleGraphConverter.graphToDotString(graph)
      Versioned[String](dotString, vg.version, vg.origin)
    },
    updateS = (vt, ast, vt1) => vt1.value != vt.value && ast.origin == ChangeOrigin.Graph,
    level = Level.None
  )

  // -------------------------------
  // versionedFullGraphV <-> fullGraphV
  // -------------------------------
  synchronize[Versioned[ViewerGraph], ViewerGraph](
    source = versionedFullGraphV,
    target = fullGraphV,
    // -------------------------------
    labelT = "[versionedFullGraphV -> fullGraphV]", // b -> a
    toT = (vg, g) => vg.value,
    updateT = (vg, g, g1) => g != g1,
    // -------------------------------
    labelS = "[fullGraphV -> versionedFullGraphV]", // a -> b
    toS = (vg, g) => Versioned[ViewerGraph](g, vg.version + 1, ChangeOrigin.Graph),
    updateS = (vg, g, vg1) => vg.value != g
  )

  // -------------------------------
  // Start the process
  // -------------------------------

//  sourceText.set(initialSource.getOrElse("""digraph "G" {}"""))

  // -------------------------------
  // fullGraphV --> visibleGraph
  // -------------------------------

  /** Graph with hidden nodes removed: ViewerGraph ~> ViewerGraph
    */
  val visibleGraph: Signal[ViewerGraph] =
    fullGraphV.signal.combineWithFn(hiddenNodes): (fullGraph: ViewerGraph, hiddenNodes) =>
      withLog("[fullGraphV -> visibleGraph]") {
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
      withLog("[visibleGraph -> visibleDOT]", level = Level.None) {
        // Note: `viewerGraphElementsToDotString` discards default attributes.
        DotText(SimpleGraphConverter.viewerGraphElementsToDotString(graph.elements.combineStyleAttributes))
      }
    }

end InternalPhases

object InternalPhases:

  def processDotText(graphviz: Graphviz, dot: DotText): Signal[Option[ReactiveSvgElement[SVG]]] =
    val graph: ViewerGraph = ???
    val dotText0           = DotText(SimpleGraphConverter.viewerGraphElementsToDotString(graph.elements))
    val dotText =
      for
        svg <- graphviz.renderToSvg(dotText0)
      yield svg.svg

    Signal.fromTry(dotText).map(Some(_))

end InternalPhases
