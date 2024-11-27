package org.jpablo.graphexplorer.viewer.formats.dot.ast

import com.softwaremill.quicklens.*
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.utils.Utils.randomUUIDSafe

import scala.annotation.tailrec

enum AttributeTarget:
  case node, edge, graph

def randomId(): String = randomUUIDSafe()

extension (ast: DotAST)

  def toViewerGraph: ViewerGraph =
    val data = findAllDirectChildren(ast.asSubgraph)
//    pprint.log(data.arrows, showFieldNames = false)
    ViewerGraph(data.toViewerGraphData, ast.id, ast.tpe)
//    pprint.log(x.data.arrows, showFieldNames = false)

//  def setDefaultTheme: DotAST =
//    ast.modify(_.children).using: children =>
//      Newline() :: Pad() :: AttrStmt("node", List(Attr("style", "filled"))) :: children

  def attachInternalAttributes: DotAST =
    EdgeStmt.resetId()
    ast.modify(_.children).using(_.map(_.attachId))

  def optimize: DotAST =
    @tailrec
    def loop(children: List[GraphElement], state: List[GraphElement] = Nil): List[GraphElement] =
      children match
        case h :: EdgeStmt(Nil, _) :: t => loop(h :: t, state) // why the focus on the 2nd element?
        case Pad() :: Newline() :: t    => loop(t, state)
        case h :: t                     => loop(t, h :: state)
        case Nil                        => state.reverse

    ast.modify(_.children).using(loop(_))
