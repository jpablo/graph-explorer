package org.jpablo.graphexplorer.viewer.state

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph

class ViewerStateSpec extends FunSuite:
  test("addNodeWithSmartConnection should add a node to the graph") {
    val viewerState = ViewerState(ProjectId("test"), _ => (), "")
    // sanity check
    assertEquals(viewerState.fullGraph.now(), ViewerGraph.minimal)
    assertEquals(viewerState.selection.size(), 0)

    viewerState.addNodeWithSmartConnection()

    // After this the recently added node is selected, so
    assertEquals(viewerState.selection.size(), 1)

    // ---- verify ---
    assertEquals(viewerState.allNodeIds().size, 1)
    assertEquals(viewerState.allArrowIds().size, 0)
  }

  test("two consecutive addNodeWithSmartConnection should add two nodes and one arrow to the graph") {
    val viewerState = ViewerState(ProjectId("test"), _ => (), "")
    // Initial state check
    assertEquals(viewerState.fullGraph.now(), ViewerGraph.minimal)

    viewerState.addNodeWithSmartConnection()
    // new node added and is currently selected
    viewerState.addNodeWithSmartConnection()
    // new node added and an arrow between the selected node and the new node

    // ---- verify ---
    assertEquals(viewerState.allNodeIds().size, 2)
    assertEquals(viewerState.allArrowIds().size, 1)
  }

  test("addArrow should add an arrow to the graph") {
    val viewerState = ViewerState(ProjectId("test"), _ => (), "")

    // Initial state check
    assertEquals(viewerState.fullGraph.now(), ViewerGraph.minimal)

    viewerState.addNodeWithSmartConnection()
    // clear selection to add just a node
    viewerState.selection.clear()
    viewerState.addNodeWithSmartConnection()
    val nodeIds = viewerState.allNodeIds().toSeq
    viewerState.addArrow(nodeIds.head, nodeIds.last)

    // ---- verify ---
    assertEquals(viewerState.allNodeIds().size, 2)
    assertEquals(viewerState.allArrowIds().size, 1)
  }
