package org.jpablo.typeexplorer.viewer.backends.graphviz

import org.jpablo.typeexplorer.viewer.graph.InheritanceGraph
import org.jpablo.typeexplorer.viewer.models
import org.jpablo.typeexplorer.viewer.plantUML.state.{DiagramOptions, ProjectSettings, SymbolOptions}

object GraphvizInheritance:

  def toGraph(
      name:            String,
      iGraph:          InheritanceGraph,
      symbolOptions:   Map[models.GraphSymbol, Option[SymbolOptions]],
      diagramOptions:  DiagramOptions,
      projectSettings: ProjectSettings
  ): String =

    val declarations =
      iGraph.namespaces.map: ns =>
        s"""${ns.symbol}[label="${ns.displayName}"]"""

    val arrows =
      iGraph.arrows.toSeq.map: (source, target) =>
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
