package org.jpablo.typeexplorer.viewer.formats

import org.jpablo.typeexplorer.viewer.graph.ViewerGraph

case class Dot(source: String):
  override def toString: String = source


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

  def fromString(source: String): Dot =
    Dot(source)

  def fromViewerGraph(graph: ViewerGraph): Dot =
    val declarations =
      graph.nodes.map: ns =>
        s""" "${ns.id}"[label="${ns.displayName}"]"""

    val arrows =
      graph.arrows.toSeq.map: a =>
        s""" "${a.source}" -> "${a.target}" """

    Dot(Digraph(declarations, arrows, "LR").toString)
