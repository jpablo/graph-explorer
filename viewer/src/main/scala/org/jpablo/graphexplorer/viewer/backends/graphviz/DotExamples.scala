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
    // Mermaid examples — flowcharts have full model support
    "Mermaid: Microservices"     -> ExampleSource("/examples/mermaid-microservices.mmd", DiagramFormat.Mermaid),
    "Mermaid: Feature Showcase"  -> ExampleSource("/examples/mermaid-showcase.mmd", DiagramFormat.Mermaid),
    // One example per remaining Mermaid diagram kind. These are RENDER-ONLY: the
    // graph model (selection/editing/attributes) reads mermaid's flowchart parse
    // db, which no other type exposes (MermaidBackend.hasFlowchartAccessors) —
    // they draw and live-update from source, but the canvas is inert.
    "Sequence Diagram"           -> ExampleSource("/examples/mermaid-sequence.mmd", DiagramFormat.Mermaid),
    "Class Diagram"              -> ExampleSource("/examples/mermaid-class.mmd", DiagramFormat.Mermaid),
    "State Diagram"              -> ExampleSource("/examples/mermaid-state.mmd", DiagramFormat.Mermaid),
    "ER Diagram"                 -> ExampleSource("/examples/mermaid-er.mmd", DiagramFormat.Mermaid),
    "User Journey"               -> ExampleSource("/examples/mermaid-journey.mmd", DiagramFormat.Mermaid),
    "Gantt Chart"                -> ExampleSource("/examples/mermaid-gantt.mmd", DiagramFormat.Mermaid),
    "Pie Chart"                  -> ExampleSource("/examples/mermaid-pie.mmd", DiagramFormat.Mermaid),
    "Mindmap"                    -> ExampleSource("/examples/mermaid-mindmap.mmd", DiagramFormat.Mermaid),
    "Timeline"                   -> ExampleSource("/examples/mermaid-timeline.mmd", DiagramFormat.Mermaid),
    "Git Graph"                  -> ExampleSource("/examples/mermaid-gitgraph.mmd", DiagramFormat.Mermaid),
    "Quadrant Chart"             -> ExampleSource("/examples/mermaid-quadrant.mmd", DiagramFormat.Mermaid),
    "XY Chart"                   -> ExampleSource("/examples/mermaid-xychart.mmd", DiagramFormat.Mermaid),
    "Sankey"                     -> ExampleSource("/examples/mermaid-sankey.mmd", DiagramFormat.Mermaid),
    "Requirement Diagram"        -> ExampleSource("/examples/mermaid-requirement.mmd", DiagramFormat.Mermaid),
    "C4 Context"                 -> ExampleSource("/examples/mermaid-c4.mmd", DiagramFormat.Mermaid),
    "Block Diagram"              -> ExampleSource("/examples/mermaid-block.mmd", DiagramFormat.Mermaid),
    "Kanban"                     -> ExampleSource("/examples/mermaid-kanban.mmd", DiagramFormat.Mermaid),
    "Packet Diagram"             -> ExampleSource("/examples/mermaid-packet.mmd", DiagramFormat.Mermaid),
    "Radar Chart"                -> ExampleSource("/examples/mermaid-radar.mmd", DiagramFormat.Mermaid),
    "Architecture"               -> ExampleSource("/examples/mermaid-architecture.mmd", DiagramFormat.Mermaid),
    "Treemap"                    -> ExampleSource("/examples/mermaid-treemap.mmd", DiagramFormat.Mermaid)
  )

  /** URL slug for an example, so `/example/<slug>` is a real, shareable route.
    *
    * Derived from the display name rather than stored beside it: adding an entry
    * to `examples` then needs no second registration to stay linkable, and a
    * renamed example simply gets a new URL instead of a stale mapping. Uniqueness
    * is what makes this safe, and DotExamplesSpec asserts it — `bySlug` is a Map,
    * so a collision would silently swallow an example rather than fail.
    */
  def slugFor(name: String): String =
    name.toLowerCase
      .map(c => if c.isLetterOrDigit then c else '-')
      .split('-')
      .filter(_.nonEmpty)
      .mkString("-")

  /** slug → (display name, source). */
  lazy val bySlug: Map[String, (String, ExampleSource)] =
    examples.map((name, source) => slugFor(name) -> (name, source)).toMap
