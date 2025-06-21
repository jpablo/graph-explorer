package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.formats.dot.DotText
import org.jpablo.graphexplorer.viewer.formats.dot.ast.*
import org.jpablo.graphexplorer.viewer.formats.dot.ast.viewerGraph.graphToDotAST
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.logging.*
import org.jpablo.graphexplorer.viewer.utils.{ChangeOrigin, Version}
import org.scalajs.dom.svg.SVG

import scala.util.{Failure, Success, Try}

case class Versioned[A](value: A, version: Version, origin: ChangeOrigin)

def syncVars[S, T](
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
end syncVars

class InternalPhases(
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
  val sourceText: Var[String] = Var("")
  // (b)
  private val versionedText = Var(Versioned("", 0, ChangeOrigin.CodeMirror))

  // (c)
  private val sourceAST: Var[Versioned[DotAST]] = Var(Versioned(DotAST.empty, 0, ChangeOrigin.CodeMirror))

  // (b)
  private val versionedFullGraphV = Var(Versioned(ViewerGraph.minimal, 0, ChangeOrigin.CodeMirror))

  // updated by the UI (a)
  val fullGraphV: Var[ViewerGraph] = Var(ViewerGraph.minimal)

  val fullGraph = fullGraphV.signal

  // -------------------------------
  // sourceText <-> versionedText
  // -------------------------------
  syncVars(
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
  // versionedText <-> sourceAST
  // -------------------------------
  syncVars[Versioned[String], Versioned[DotAST]](
    source = versionedText,
    target = sourceAST,
    // -------------------------------
    labelT = "[versionedText -> sourceAST]", // b -> c
    toT = { (vt, ast) =>
      val newAST =
        DotText(vt.value).parseAST match
          case Failure(f) =>
            editorError.set(Option(f.getMessage))
            // consider creating a new AST with the error message
            DotAST.empty
          case Success(asts) =>
            editorError.set(None)
            asts.headOption.getOrElse(DotAST.empty)

      Versioned[DotAST](newAST, vt.version, vt.origin)
    },
    updateT = (vt, ast, ast1) => ast.value != ast1.value && ast1.origin == ChangeOrigin.CodeMirror,
    // -------------------------------
    labelS = "[sourceAST -> versionedText]", // c -> b
    toS = { (vt, ast) =>
      val newSource = ast.value.optimize.render(keepInternal = false)
      Versioned[String](newSource, ast.version, ast.origin)
    },
    updateS = (vt, ast, vt1) => vt1.value != vt.value && ast.origin == ChangeOrigin.Graph,
    level = Level.None
  )

  // -------------------------------
  // sourceAST <-> versionedFullGraphV
  // -------------------------------
  syncVars(
    source = sourceAST,
    target = versionedFullGraphV,
    // -------------------------------
    labelT = "[sourceAST -> versionedFullGraphV]", // c -> b
    toT = (ast: Versioned[DotAST], vg) => Versioned[ViewerGraph](ast.value.toViewerGraph, ast.version, ast.origin),
    updateT = (ast, vg, vg1) => vg.value != vg1.value && ast.origin == ChangeOrigin.CodeMirror,
    // -------------------------------
    labelS = "[versionedFullGraphV -> sourceAST]", // b -> c
    toS = (ast, vg) => Versioned[DotAST](graphToDotAST(vg.value), vg.version, vg.origin),
    updateS = (ast, vg, ast1) => ast.value != ast1.value && vg.origin == ChangeOrigin.Graph
  )

  // -------------------------------
  // versionedFullGraphV <-> fullGraphV
  // -------------------------------
  syncVars(
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

  sourceText.set(initialSource.getOrElse(""))

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
  // visibleGraph -> visibleAST -> visibleDOT
  // -------------------------------
  val visibleAST: Signal[DotAST] =
    visibleGraph.map(graph => withLog("[visibleGraph -> visibleAST]")(graphToDotAST(graph)))

  val visibleDOT: Signal[DotText] =
    visibleAST
      .map(ast => withLog("[visibleAST -> visibleDOT]", level = Level.None)(DotText(ast.render(keepInternal = true))))

end InternalPhases

object InternalPhases:
  def processDotText(dot: DotText): Signal[Option[ReactiveSvgElement[SVG]]] =
    val dotText =
      for
        asts <- dot.parseAST
        ast  <- Try(asts.head)
        graph = ast.toViewerGraph.removeUnsupportedFeatures.withDefaultTheme
      yield DotText(graphToDotAST(graph).render())

    Signal.fromTry(dotText).flatMapSwitch(_.toSvg).map(_.map(_.svg))
