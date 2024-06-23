package org.jpablo.typeexplorer.viewer.backends.graphviz

import org.jpablo.typeexplorer.viewer.graph.ViewerGraph
import org.jpablo.typeexplorer.viewer.models
import org.jpablo.typeexplorer.viewer.components.state.{DiagramOptions, ProjectSettings, SymbolOptions}

object Graphviz:

  def toDot(
      name:            String,
      graph:           ViewerGraph,
      symbolOptions:   Map[models.ViewerNodeId, Option[SymbolOptions]] = Map.empty,
      diagramOptions:  DiagramOptions = DiagramOptions(),
      projectSettings: ProjectSettings = ProjectSettings()
  ): String =

    val declarations =
      graph.nodes.map: ns =>
        s"""${ns.nodeId}[label="${ns.displayName}"]"""

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

end Graphviz
