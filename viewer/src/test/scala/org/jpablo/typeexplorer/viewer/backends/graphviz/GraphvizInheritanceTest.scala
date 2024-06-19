package org.jpablo.typeexplorer.viewer.backends.graphviz

import org.jpablo.typeexplorer.viewer.backends.graphviz.GraphvizInheritance.toGraph
import org.jpablo.typeexplorer.viewer.examples.Example1
import org.jpablo.typeexplorer.viewer.plantUML.state.{DiagramOptions, ProjectSettings}

class GraphvizInheritanceTest extends munit.FunSuite {
 test("dot generation") {
   val g = toGraph("laminar", Example1.diagram, Map.empty, DiagramOptions(), ProjectSettings())
   println(g)
 }
}
