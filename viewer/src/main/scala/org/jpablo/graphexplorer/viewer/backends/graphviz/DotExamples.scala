package org.jpablo.graphexplorer.viewer.backends.graphviz

import org.jpablo.graphexplorer.viewer.backends.DiagramFormat

import scala.collection.immutable.VectorMap

object DotExamples:

  case class ExampleSource(path: String, format: DiagramFormat)

  val emptyGraph: ExampleSource = ExampleSource("/examples/empty-graph.dot", DiagramFormat.DOT)
  val emptyMermaidGraph: ExampleSource = ExampleSource("/examples/empty-mermaid.mmd", DiagramFormat.Mermaid)

  val examples: VectorMap[String, ExampleSource] = VectorMap(
    "Empty Graph (Graphviz)"      -> emptyGraph,
    "Empty Graph (MermaidJS)"     -> emptyMermaidGraph,
    "Finite State Machine"        -> ExampleSource("/examples/finite-state-machine.dot", DiagramFormat.DOT),
    "Groups"                      -> ExampleSource("/examples/groups.dot", DiagramFormat.DOT),
    // Fixme:
    // "Color Wheel"          -> "/examples/glitches/color-wheel.dot",
    "Data Structures"            -> ExampleSource("/examples/data-structures.dot", DiagramFormat.DOT),
    "HTML"                       -> ExampleSource("/examples/html.dot", DiagramFormat.DOT),
    // Fix performance when selecting
    // "Network Map"      -> "/examples/glitches/network-map.dot",
    "sbt dependencies"           -> ExampleSource("/examples/sbt-project-dependencies.dot", DiagramFormat.DOT),
    "Logo"                       -> ExampleSource("/examples/logo.dot", DiagramFormat.DOT),
    "Colors"                     -> ExampleSource("/examples/neato/colors.dot", DiagramFormat.DOT),
    "Twelve Colors"              -> ExampleSource("/examples/neato/twelve-colors.dot", DiagramFormat.DOT),
    // Mermaid examples
    "Mermaid: Microservices"     -> ExampleSource("/examples/mermaid-microservices.mmd", DiagramFormat.Mermaid)
  )
