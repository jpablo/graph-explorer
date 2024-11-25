package org.jpablo.graphexplorer.viewer.formats.dot.ast.viewerGraph

import org.jpablo.graphexplorer.viewer.formats.dot.ast.{Attr, DotAST, DotNodeId, EdgeStmt, NodeStmt}
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph

def viewerGraphDot(graph: ViewerGraph): DotAST =
  val nodeStmts = graph.nodeById.values.map { node =>
    NodeStmt(
      DotNodeId(node.id.value),
      node.publicAttrs.values.map { case (key, value) =>
        Attr(key, value)
      }.toList
    )
  }

  val edgeStmts = graph.arrows.map { arrow =>
    EdgeStmt(
      List(
        DotNodeId(arrow.source.value),
        DotNodeId(arrow.target.value)
      ),
      arrow.publicAttrs.values.map { case (key, value) =>
        Attr(key, value)
      }.toList
    )
  }

  // Combine all elements into a DotAST
  DotAST(
    tpe      = "digraph",
    children = (nodeStmts ++ edgeStmts).toList,
    id       = None
  )
