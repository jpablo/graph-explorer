package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.ownership.Owner
import munit.FunSuite
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.graph.ViewerGraphElements.initialGroup
import org.jpablo.graphexplorer.viewer.models.ViewerNode.node

class SourceFlowSpec extends FunSuite:
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

  test("Updating the source text should update the graph") {
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

  test("Updating the graph should trigger an update to the source text") {
    val viewerState = ViewerState(ProjectId("test"), _ => (), "")
    given Owner = viewerState.owner

    // Initial state check
    assertEquals(viewerState.sourceText.now(), PersistedState.minimalGraphText)
    assertEquals(viewerState.fullGraph.now(), ViewerGraph.minimal)

    // Update the graph by adding a node
    viewerState.addNodeWithSmartConnection()

    // Verify that the source text was updated to reflect the new node

    val updatedGraph = viewerState.fullGraph.now()
    // The node count in the graph should match what we expect
    assertEquals(updatedGraph.nodes.size, 1, "Graph should have exactly one node")

    val graphId = updatedGraph.id
    val nodeId = updatedGraph.nodeIds.head

    val expectedSource =
      s"""digraph "$graphId" {
         |    "$nodeId" [label=""];
         |}""".stripMargin

    assertEquals(viewerState.sourceText.now(), expectedSource, "Source text should be updated to reflect the new node")
  }
