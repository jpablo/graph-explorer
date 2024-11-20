package org.jpablo.graphexplorer.viewer.state

import com.raquo.laminar.api.L.*
import com.raquo.airstream.core.Signal
import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.formats.dot.DotText
import org.jpablo.graphexplorer.viewer.formats.dot.ast.*
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models
import org.jpablo.graphexplorer.viewer.models.NodeId

class SourceFlow(
    initialSource: String,
    hiddenNodes:   Signal[Set[NodeId]],
    resetView:     () => Unit
)(using Owner):

  // source of truth
  val source: Var[(String, DotAST)] = Var(("", DotAST.empty))

  /** parse source: String ~> DotAST
    */
  val sourceText: Var[String] =
    source.zoom(_._1): (_, newSource) =>
      (
        newSource,
        DotText(newSource)
          .parseAST.headOption
          .getOrElse(DotAST.empty)
          .attachInternalAttributes
      )

  /** render AST: DotAST ~> String
    */
  val sourceAST: Var[DotAST] =
    source.zoom(_._2)((_, newAST) => (newAST.optimize.render(keepInternal = false), newAST))

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
    fullAST.map(_.toViewerGraph)

  /** AST with hidden nodes removed: DotAST ~> DotAST
    */
  val visibleAST: Signal[DotAST] =
    fullAST
      .combineWith(hiddenNodes.signal)
      .map: (fullAST, hiddenNodes) =>
        fullAST
          .removeUnsupportedFeatures
          .removeNodes(hiddenNodes)
          .setDefaultTheme
      .tapEach(_ => resetView())

  // transform visible AST back to Visible Dot
  // DotAST ~> Dot
  val visibleDOT: Signal[DotText] =
    visibleAST.map(_.renderToDot)

  val visibleGraph: Signal[ViewerGraph] =
    visibleAST.map(_.toViewerGraph)

end SourceFlow
