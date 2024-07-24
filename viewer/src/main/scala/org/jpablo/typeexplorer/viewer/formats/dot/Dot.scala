package org.jpablo.typeexplorer.viewer.formats.dot

import com.raquo.laminar.api.L.*
import org.jpablo.typeexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.typeexplorer.viewer.components.SvgDotDiagram
import org.jpablo.typeexplorer.viewer.formats.dot.ast.{DiGraph, EdgeStmt}
import org.jpablo.typeexplorer.viewer.graph.ViewerGraph
import org.jpablo.typeexplorer.viewer.models.{Arrow, ViewerNode}


case class Dot(value: String):
  override def toString: String =
    value

  val ast: List[DiGraph] =
    DotParserT.parse(value).getOrElse(Nil)


object Dot:
  extension (dot: Dot)
    def toSvgDiagram: Signal[SvgDotDiagram] =
      (new Graphviz).renderDot(dot)

    def toViewerGraph: ViewerGraph =
      val diGraph = dot.ast.head // Assuming there's only one digraph

      val nodes =
        diGraph.children
          .collect { case EdgeStmt(_, edgeList, _) =>
            edgeList.map(_.id).map(ViewerNode.node)
          }
          .flatten
          .toSet

      val arrows =
        diGraph.children
          .collect {
            case EdgeStmt(_, edgeList, _) if edgeList.size >= 2 =>
              edgeList.sliding(2).map { case List(source, target) =>
                Arrow(source.id, target.id)
              }
          }
          .flatten
          .toSet

      ViewerGraph(arrows, nodes)


  extension (graph: ViewerGraph)
    def toDot: Dot =
      val declarations =
        graph.nodes.map: ns =>
          s""" "${ns.id}"[label="${ns.displayName}"]"""

      val arrows =
        graph.arrows.toSeq.map: a =>
          s""" "${a.source}" -> "${a.target}" """

      Dot(
        Digraph(declarations, arrows, "LR").toString
      )
end Dot


case class Digraph(
  declarations: Set[String],
  arrows:       Seq[String],
  rankdir:      "LR" | "TB"
):
  override def toString: String =
    s"""
       |digraph G {
       | rankdir=$rankdir
       | ${declarations.mkString("\n  ")}
       |
       | ${arrows.mkString("\n  ")}
       |
       |}
       |""".stripMargin

