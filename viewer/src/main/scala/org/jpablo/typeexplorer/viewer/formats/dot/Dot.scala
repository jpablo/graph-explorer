package org.jpablo.typeexplorer.viewer.formats.dot

import com.raquo.airstream.core.Signal
import org.jpablo.typeexplorer.viewer.formats.dot.ast.{AttrStmt, DiGraph, EdgeStmt, Newline, Pad, StmtSep}
import org.jpablo.typeexplorer.viewer.graph.ViewerGraph
import org.jpablo.typeexplorer.viewer.models.{Arrow, ViewerNode}
import org.scalajs.dom

import scala.scalajs.js
import scala.scalajs.js.JSON
import upickle.default.*

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
    val json = JSON.stringify(parse(source))
    val ast = read[List[DiGraph]](json)
    val diGraph = ast.head // Assuming there's only one digraph

    val nodes =
      diGraph.children
        .collect { case EdgeStmt(_, edgeList, _) =>
          edgeList.map(node => ViewerNode.node(node.id))
        }
        .flatten
        .toSet

    val arrows = diGraph.children
      .collect {
        case EdgeStmt(_, edgeList, _) if edgeList.size >= 2 =>
          edgeList.sliding(2).map { case List(source, target) =>
            Arrow(source.id, target.id)
          }
      }
      .flatten
      .toSet

    Signal.fromValue(ViewerGraph(arrows, nodes))

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
