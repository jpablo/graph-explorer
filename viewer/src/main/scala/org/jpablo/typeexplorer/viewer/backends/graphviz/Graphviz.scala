package org.jpablo.typeexplorer.viewer.backends.graphviz

import com.raquo.laminar.api.L.*
import org.jpablo.typeexplorer.viewer.components.SvgDiagram
import org.jpablo.typeexplorer.viewer.graph.ViewerGraph
import org.jpablo.typeexplorer.viewer.models
import org.jpablo.typeexplorer.viewer.components.state.{DiagramOptions, ProjectSettings, SymbolOptions}
import org.scalajs.dom.SVGSVGElement

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

class Graphviz:
  private val instance: Future[js.Dynamic] =
    js.Dynamic.global.Viz
      .instance()
      .asInstanceOf[js.Promise[js.Dynamic]]
      .toFuture

  private def renderSVGElement(g: String): Future[SVGSVGElement] =
    instance.map(_.renderSVGElement(g).asInstanceOf[SVGSVGElement])

  def renderDot(s: String): Signal[SvgDiagram] =
    Signal
      .fromFuture(renderSVGElement(s).map(SvgDiagram.apply))
      .map(_.getOrElse(SvgDiagram.empty))

object Graphviz:

  extension (graph: ViewerGraph)
    def toDot(
        name:            String,
        symbolOptions:   Map[models.NodeId, Option[SymbolOptions]] = Map.empty,
        diagramOptions:  DiagramOptions = DiagramOptions(),
        projectSettings: ProjectSettings = ProjectSettings()
    ): String =

      val declarations =
        graph.nodes.map: ns =>
          s"""${ns.id}[label="${ns.displayName}"]"""

      val arrows =
        graph.arrows.toSeq.map: a =>
          s""" ${a.source} -> ${a.target}"""

      s"""
       |digraph G {
       | rankdir=LR
       | ${declarations.mkString("\n  ")}
       |
       | ${arrows.mkString("\n  ")}
       |
       |}
       |""".stripMargin
