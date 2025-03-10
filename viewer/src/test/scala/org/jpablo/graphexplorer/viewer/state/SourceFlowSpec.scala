package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.ownership.Owner
import munit.ScalaCheckSuite
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph

class SourceFlowSpec extends ScalaCheckSuite:
  test("fullAST sanity check"):
    val viewerState = ViewerState(ProjectId("test"), _ => (), "")
    given Owner = viewerState.owner
    val sourceFlow = viewerState.sourceFlow

    assertEquals(sourceFlow.fullGraphV.now(), ViewerGraph.minimal)

    val visibleDot =
      """|digraph "G" {
         |    node [sides="5"];
         |    edge [
         |        dir="both",
         |        arrowtail="none"
         |    ];
         |}""".stripMargin
    assertEquals(sourceFlow.visibleDOT.observe().now().value, visibleDot)
