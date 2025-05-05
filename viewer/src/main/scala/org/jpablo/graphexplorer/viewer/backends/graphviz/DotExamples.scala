package org.jpablo.graphexplorer.viewer.backends.graphviz

import scala.collection.immutable.VectorMap

object DotExamples:

  val emptyGraph = "/examples/empty-graph.dot"
  
  val examples = VectorMap(
    "Empty Graph"          -> emptyGraph,
    "Finite State Machine" -> "/examples/finite-state-machine.dot",
    "Groups"               -> "/examples/groups.dot",
    // Fixme:
    // "Color Wheel"          -> "/examples/glitches/color-wheel.dot",
    "Data Structures" -> "/examples/data-structures.dot",
    "HTML"            -> "/examples/html.dot",
    // Fix performance when selecting
    // "Network Map"      -> "/examples/glitches/network-map.dot",
    "sbt dependencies" -> "/examples/sbt-project-dependencies.dot",
    "Logo"             -> "/examples/logo.dot",
    "Colors"           -> "/examples/neato/colors.dot",
    "Twelve Colors"    -> "/examples/neato/twelve-colors.dot"
  )
