package org.jpablo.graphexplorer.viewer.formats.dot.ast

import com.softwaremill.quicklens.*
import org.jpablo.graphexplorer.viewer.formats.dot.DotText
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.{NodeId, ViewerNode}
import org.jpablo.graphexplorer.viewer.extensions.*

import scala.annotation.tailrec

extension (ast: DotAST)

  def renderToDot: DotText =
    DotText(ast.render(true))

  def toViewerGraph: ViewerGraph =
    ViewerGraph(
      arrows = ast.allArrows,
      nodes  = ast.allNodesIds.map(ViewerNode.node)
    )

  def addEdge(source: NodeId, target: NodeId): DotAST =
    val newEdge = EdgeStmt(List(DotNodeId(source.value), DotNodeId(target.value)), Nil)
    ast.modify(_.children).using(_ ++ List(Newline(), Pad(), newEdge, Newline()))

  def withGraphAttributes(attrs: GraphElementAttributes): DotAST =
    var attrMap = attrs.toMap

    val children2 =
      ast.children.map:
        case a @ AttrStmt("graph", List(Attr(name, _))) =>
          if attrMap.contains(name) then
            val attr2 = attrMap(name)
            attrMap -= name
            attr2
          else
            a
        case other => other

    ast.copy(children = attrMap.values.toList ++ children2)

  def getGraphAttributes: GraphElementAttributes =
    val attrs: List[(String, String | AttrEq)] = ast.children.collect:
      case AttrStmt("graph", List(Attr(name, value))) => name -> value

    GraphElementAttributes(
      rankdir = attrs.collectFirst { case ("rankdir", v) => v.toString },
      label   = attrs.collectFirst { case ("label", v) => v.toString },
//      size = attrs.collectFirst { case ("size", v) => v.toString.split(",").toList match { case List(w, h) => (w.toDouble, h.toDouble) } },
      splines = attrs.collectFirst { case ("splines", v) => v.toString },
      bgcolor = attrs.collectFirst { case ("bgcolor", v) => v.toString },
//      margin = attrs.collectFirst { case ("margin", v) => v.toString.split(",").toList match { case List(x, y) => (x.toDouble, y.toDouble) } },
      fontname  = attrs.collectFirst { case ("fontname", v) => v.toString },
      fontsize  = attrs.collectFirst { case ("fontsize", v) => v.toString.toDouble },
      fontcolor = attrs.collectFirst { case ("fontcolor", v) => v.toString },
      overlap   = attrs.collectFirst { case ("overlap", v) => v.toString }
    )

  /** Unsupported features:
    *   - graph size (results in an incorrect layout)
    */
  def removeUnsupportedFeatures: DotAST =
    ast.modify(_.children).using:
      _.filter:
        case AttrStmt("graph", List(Attr("size", _))) => false
        case _                                        => true

  def setDefaultTheme: DotAST =
    ast.modify(_.children).using: children =>
      AttrStmt("node", List(Attr("style", "filled"))) :: children

  def attachInternalAttributes: DotAST =
    EdgeStmt.resetId()
    ast.modify(_.children).using(_.map(_.attachId))

  def removeNodes(idsToRemove: Set[String]): DotAST =
    @tailrec
    def optimize(children: List[GraphElement], state: List[GraphElement] = Nil): List[GraphElement] =
      children match
        case h :: EdgeStmt(Nil, _) :: t => optimize(h :: t, state) // why the focus on the 2nd element?
        case Pad() :: Newline() :: t    => optimize(t, state)
        case h :: t                     => optimize(t, h :: state)
        case Nil                        => state.reverse

    def dedup(lst: List[GraphElement]): List[GraphElement] =
      lst
        .foldLeft((List.empty[GraphElement], Set.empty[GraphElement])):
          case ((acc, visited), e: EdgeStmt) if e in visited => (acc, visited)
          case ((acc, visited), n: NodeStmt) if n in visited => (acc, visited)
          case ((acc, visited), e)                           => (e :: acc, visited + e)
        ._1
        .reverse

    ast
      .modify(_.children).using(_.flatMap(_.removeGraphNodes(idsToRemove)))
      .modify(_.children).using(optimize(_))
      .modify(_.children).using(dedup)
