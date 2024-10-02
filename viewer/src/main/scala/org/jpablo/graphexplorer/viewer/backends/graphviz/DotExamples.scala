package org.jpablo.graphexplorer.viewer.backends.graphviz

import scala.collection.immutable.VectorMap

object DotExamples:

  val examples = VectorMap(
    "Empty Graph"          -> "/examples/empty-graph.dot",
    "Finite State Machine" -> "/examples/finite-state-machine.dot",
    "Clusters"             -> "/examples/clusters.dot",
    "HTML"                 -> "/examples/html.dot",
    "Styles"               -> "/examples/styles.dot",
  )
