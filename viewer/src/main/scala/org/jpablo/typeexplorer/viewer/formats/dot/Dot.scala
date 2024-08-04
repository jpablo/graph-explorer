package org.jpablo.typeexplorer.viewer.formats.dot

import com.raquo.laminar.api.L.*
import org.jpablo.typeexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.typeexplorer.viewer.components.SvgDotDiagram
import org.jpablo.typeexplorer.viewer.formats.dot.ast.{DiGraph, EdgeStmt}
import org.jpablo.typeexplorer.viewer.graph.ViewerGraph
import org.jpablo.typeexplorer.viewer.models.{Arrow, ViewerNode}
import org.scalajs.dom
import upickle.default.*

import scala.util.{Failure, Success}

case class Dot(value: String):
  override def toString: String =
    value

  // TODO: handle errors
  val buildAST: List[DiGraph] =
    DotParserT.parse(value) match
      case Failure(exception) =>
        dom.console.log("---> 2.5 Error in DotParserT.parse !")
        dom.console.log(exception.toString)
        dom.console.log(value)
        dom.console.log("<--- 2.5 ")
        List.empty
      case Success(value) =>
        value

object Dot:
  private val gvInstance = new Graphviz

  extension (diGraph: DiGraph)
    def toDot: Dot =
      Dot(diGraph.render)

    def toViewerGraph: ViewerGraph =
      ViewerGraph(
        arrows = diGraph.allArrows.map(Arrow.apply.tupled),
        nodes  = diGraph.allNodesIds.map(ViewerNode.node)
      )

  extension (dot: Dot)
    def toSvgDiagram: Signal[SvgDotDiagram] =
      gvInstance.renderDot(dot)

    def toViewerGraph: ViewerGraph =
      // TODO: handle errors
      // Assuming there's only one digraph
      dot.buildAST.map(_.toViewerGraph).head

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
