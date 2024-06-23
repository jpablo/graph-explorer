package org.jpablo.typeexplorer.viewer.backends.graphviz

import org.jpablo.typeexplorer.viewer.graph.ViewerGraph
import org.jpablo.typeexplorer.viewer.models
import org.jpablo.typeexplorer.viewer.components.state.{DiagramOptions, ProjectSettings, SymbolOptions}

object GraphvizInheritance:

  def toDot(
      name:            String,
      graph:           ViewerGraph,
      symbolOptions:   Map[models.GraphSymbol, Option[SymbolOptions]] = Map.empty,
      diagramOptions:  DiagramOptions = DiagramOptions(),
      projectSettings: ProjectSettings = ProjectSettings()
  ): String =

    val declarations =
      graph.namespaces.map: ns =>
        s"""${ns.symbol}[label="${ns.displayName}"]"""

    val arrows =
      graph.arrows.toSeq.map: (source, target) =>
        s""" $source -> $target"""

    s"""
       |digraph G {
       | rankdir=LR
       | ${declarations.mkString("\n  ")}
       |
       | ${arrows.mkString("\n  ")}
       |
       |}
       |""".stripMargin

end GraphvizInheritance
