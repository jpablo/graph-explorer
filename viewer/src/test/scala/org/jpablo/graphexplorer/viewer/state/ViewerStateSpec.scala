package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.ownership.Owner
import munit.FunSuite
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.graph.ViewerGraphElements.initialGroup
import org.jpablo.graphexplorer.viewer.models.ViewerNode.node

class ViewerStateSpec extends FunSuite:
  test("Sanity check") {
    val viewerState = ViewerState(ProjectId("test"), _ => (), "")
    given Owner = viewerState.owner

    assertEquals(viewerState.sourceFlow.fullGraphV.now(), ViewerGraph.minimal)

    val visibleDot =
      """|digraph "G" {
         |    node [sides="5"];
         |    edge [
         |        dir="both",
         |        arrowtail="none"
         |    ];
         |}""".stripMargin
    assertEquals(viewerState.visibleDOT.observe().now().value, visibleDot)
  }

  test("Set source text") {
    val viewerState = ViewerState(ProjectId("test"), _ => (), "")
    given Owner = viewerState.owner

    val newSource =
      """|digraph "G" {
         |    "a" [label="A", other="value"];
         |}""".stripMargin

    viewerState.sourceText.set(newSource)

    val graph = viewerState.fullGraph.now()

    assertEquals(graph.nodes, Map(node("a", "label" -> "A", "other" -> "value")))
    assertEquals(graph.groups, Map(initialGroup))
    assert(graph.arrows.isEmpty)
    assert(graph.memberships.isEmpty)
  }
