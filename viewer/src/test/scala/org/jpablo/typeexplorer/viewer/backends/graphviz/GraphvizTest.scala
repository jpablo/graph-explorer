package org.jpablo.typeexplorer.viewer.backends.graphviz

import org.jpablo.typeexplorer.viewer.backends.graphviz.Graphviz.toDot
import org.jpablo.typeexplorer.viewer.examples.Example1

class GraphvizTest extends munit.FunSuite {
 test("dot generation") {
   val g = Example1.graph.toDot("laminar")
     println(g)
 }
}
