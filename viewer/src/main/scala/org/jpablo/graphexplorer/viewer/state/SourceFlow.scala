package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.formats.dot.Dot
import org.jpablo.graphexplorer.viewer.formats.dot.Dot.*
import org.jpablo.graphexplorer.viewer.formats.dot.ast.DotAST
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models
import org.jpablo.graphexplorer.viewer.models.NodeId

class SourceFlow(initialSource: String, hiddenNodesV: Signal[Set[NodeId]]):
  val source: Var[String] = Var(initialSource)

  // 1. parse source
  // String ~> Dot ~> DiGraphAST
  val fullAST: Signal[DotAST] =
    source.signal.map: src =>
      Dot(src).buildAST.headOption
        .map(_.attachInternalAttributes)
        .getOrElse(DotAST.empty)

  // 2. DiGraphAST ~> ViewerGraph
  // Arrows are assigned consecutive ids starting from 1
  val fullGraph: Signal[ViewerGraph] =
    fullAST.map(_.toViewerGraph)

  // 3. Remove hidden nodes from Dot AST
  // DiGraphAST ~[removeNodes]~> DiGraphAST
  val visibleAST: Signal[DotAST] =
    fullAST
      .combineWith(hiddenNodesV.signal)
      .map: (fullAST, hiddenNodes) =>
        fullAST
          .removeUnsupportedFeatures
          .removeNodes(hiddenNodes.map(_.value))
          .setDefaultTheme

  // 4. transform visible AST back to Visible Dot
  // DiGraphAST ~> Dot
  val visibleDOT: Signal[Dot] =
    visibleAST.map(_.toDot)

  val visibleGraph: Signal[ViewerGraph] =
    visibleAST.map(_.toViewerGraph)

end SourceFlow
