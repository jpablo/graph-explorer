package org.jpablo.graphexplorer.viewer.formats.dot

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.graphexplorer.viewer.formats.dot.ast.DiGraphAST
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.{Arrow, ViewerNode}
import org.scalajs.dom.SVGSVGElement

case class Dot(value: String):
//  org.scalajs.dom.console.log(value)

  override def toString: String =
    value

  // TODO: handle errors
  val buildAST: List[DiGraphAST] =
    DotParserT.parse(value).getOrElse(Nil)

object Dot:
  private val gvInstance = new Graphviz

  extension (diGraph: DiGraphAST)
    def toDot: Dot =
      Dot(diGraph.render)

    def toViewerGraph: ViewerGraph =
      ViewerGraph(
        arrows = diGraph.allArrows.map(Arrow.apply),
        nodes  = diGraph.allNodesIds.map(ViewerNode.node)
      )

  extension (dot: Dot)
    def toSvg: Signal[SVGSVGElement] =
      gvInstance.renderToSvg(dot)

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
