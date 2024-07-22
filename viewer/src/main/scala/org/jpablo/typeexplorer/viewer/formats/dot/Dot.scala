package org.jpablo.typeexplorer.viewer.formats.dot

import com.raquo.airstream.core.Signal
import org.jpablo.typeexplorer.viewer.formats.dot.ast.DiGraph
import org.jpablo.typeexplorer.viewer.graph.ViewerGraph
import org.scalajs.dom

import scala.scalajs.js
import scala.scalajs.js.JSON
import upickle.default.*
//import com.github.plokhotnyuk.jsoniter_scala.macros.*
//import com.github.plokhotnyuk.jsoniter_scala.core.*


case class DotString(value: String) extends AnyVal:
  override def toString: String = value

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
  def toViewerGraph(source: String, parse: String => js.Object): Signal[ViewerGraph] =
    dom.console.log("Dot.toViewerGraph")
    val json = JSON.stringify(parse(source))
    dom.console.log(json)
    val ast = read[List[DiGraph]](json)
    println(ast)
    // TODO: use the ast to create a ViewerGraph
    Signal.fromValue(ViewerGraph(Set.empty, Set.empty))


  extension (graph: ViewerGraph)
    def toDot: DotString =
      val declarations =
        graph.nodes.map: ns =>
          s""" "${ns.id}"[label="${ns.displayName}"]"""

      val arrows =
        graph.arrows.toSeq.map: a =>
          s""" "${a.source}" -> "${a.target}" """

      DotString(
        Digraph(declarations, arrows, "LR").toString
      )
