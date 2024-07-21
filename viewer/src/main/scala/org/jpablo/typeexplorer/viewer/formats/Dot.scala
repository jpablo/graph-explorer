package org.jpablo.typeexplorer.viewer.formats

import com.raquo.airstream.core.Signal
import org.jpablo.typeexplorer.viewer.components.SvgDotDiagram
import org.jpablo.typeexplorer.viewer.graph.ViewerGraph
import org.jpablo.typeexplorer.viewer.models.{Arrow, ViewerNode}
import org.scalajs.dom

import scala.scalajs.js

case class Dot(source: String, parse: String => js.Object):
  override def toString: String = source

  lazy val ast: js.Object =
    parse(source)

  def toViewerGraph: Signal[ViewerGraph] =
    dom.console.log(ast)
    Signal.fromValue(ViewerGraph(Set.empty, Set.empty))

//  def toViewerGraph(render: Dot => Signal[SvgDotDiagram]): Signal[ViewerGraph] =
//    render(this).map: diagram =>
//      def textContent(cls: String) =
//        diagram.ref.querySelectorAll(s"$cls > title").map(_.textContent)
//
//      val arrows = textContent(".edge").flatMap(Arrow.fromString).toSet
//      val nodes = textContent(".node").map(ViewerNode.node).toSet
//
//      ViewerGraph(arrows, nodes)
//
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

object Dot:

  extension (graph: ViewerGraph)
    def toDot: Dot =
      val declarations =
        graph.nodes.map: ns =>
          s""" "${ns.id}"[label="${ns.displayName}"]"""

      val arrows =
        graph.arrows.toSeq.map: a =>
          s""" "${a.source}" -> "${a.target}" """

      Dot(Digraph(declarations, arrows, "LR").toString, ???)
