package org.jpablo.graphexplorer.viewer.state

import com.raquo.laminar.api.L.*
import com.raquo.airstream.core.Signal
import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.formats.dot.DotText
import org.jpablo.graphexplorer.viewer.formats.dot.ast.*
import org.jpablo.graphexplorer.viewer.formats.dot.ast.viewerGraph.graphToDotAST
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models
import org.jpablo.graphexplorer.viewer.models.NodeId

class SourceFlow(
    initialSource: String,
    hiddenNodes:   Signal[Set[NodeId]],
    resetView:     () => Unit
)(using Owner):

  // source of truth
  private val sourceTextAndAST: Var[(source: Path, ast: DotAST)] = Var(("", DotAST.empty))

  /** parse source on write: String ~> DotAST
    */
  val sourceText: Var[Path] =
    sourceTextAndAST.zoom(_.source): (_, newSource) =>
      val newAST =
        DotText(newSource).parseAST.headOption
          .getOrElse(DotAST.empty)
          .attachInternalAttributes
      (newSource, newAST)

  /** render AST on write: DotAST ~> String
    */
  val sourceAST: Var[DotAST] =
    sourceTextAndAST.zoom(_.ast): (_, newAST: DotAST) =>
      (newAST.optimize.render(keepInternal = false), newAST)

  // initial setup
  sourceText.set(initialSource)

  /** AST with internal annotations: DotAST ~> DotAST
    */
  val fullAST: Signal[DotAST] =
    sourceAST.signal

  /** DotAST ~> ViewerGraph
    *
    * Arrows are assigned consecutive ids starting from 1
    */
  val fullGraph: Signal[ViewerGraph] =
    fullAST.map(_.toViewerGraph).tapEach(graph => pprint.log(graph))

  /** Graph with hidden nodes removed: ViewerGraph ~> ViewerGraph
    */
  val visibleGraph: Signal[ViewerGraph] =
    fullGraph
      .combineWith(hiddenNodes.signal)
      .map: (fullGraph, hiddenNodes) =>
        fullGraph
          .removeUnsupportedFeatures
          .removeNodes(hiddenNodes)
          .setDefaultTheme
      .tapEach(_ => resetView())

  val visibleAST: Signal[DotAST] =
    visibleGraph.map(graphToDotAST)

  // transform visible AST back to Visible Dot
  // DotAST ~> Dot
  val visibleDOT: Signal[DotText] =
    visibleAST.map(_.renderToDot)

end SourceFlow
