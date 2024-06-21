package org.jpablo.typeexplorer.viewer.backends.graphviz

import org.jpablo.typeexplorer.viewer.backends.graphviz.GraphvizInheritance.toDot
import org.jpablo.typeexplorer.viewer.examples.Example1
import org.jpablo.typeexplorer.viewer.components.state.{DiagramOptions, ProjectSettings}

class GraphvizInheritanceTest extends munit.FunSuite {
 test("dot generation") {
   val g = toDot("laminar", Example1.diagram)
   println(g)
 }
}
