package org.jpablo.graphexplorer.viewer.state

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.formats.dot.ast.{AttrValue, AttributeTarget}
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.*
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

  test("rootTargetAttributesUpdates should update root attributes for the specified target") {
    val viewerState = ViewerState(ProjectId("test"), _ => (), "")

    // Initial state check
    assertEquals(viewerState.fullGraph.now(), ViewerGraph.minimal)

    val graphUpdates = viewerState.rootTargetAttributesUpdates(AttributeTarget.graph)

    // Update graph attributes
    graphUpdates.update(_ + (Color.attrId -> AttrValue("blue")))

    // Verify the updates are applied
    val updatedGraph = viewerState.fullGraph.now()
    assertEquals(
      updatedGraph.getRootAttributes(AttributeTarget.graph).get(Color.attrId),
      Some(AttrValue("blue")),
      "Root graph attributes should be updated"
    )

    // Get the AttributesUpdates for node target
    val nodeUpdates = viewerState.rootTargetAttributesUpdates(AttributeTarget.node)

    // Update node attributes
    nodeUpdates.update(_ + (Shape.attrId -> AttrValue("box")))

    // Verify the updates are applied
    val updatedGraph2 = viewerState.fullGraph.now()
    assertEquals(
      updatedGraph2.getRootAttributes(AttributeTarget.node).get(Shape.attrId),
      Some(AttrValue("box")),
      "Root node attributes should be updated"
    )

    // Get the AttributesUpdates for edge target
    val edgeUpdates = viewerState.rootTargetAttributesUpdates(AttributeTarget.edge)

    // Update edge attributes
    edgeUpdates.update(_ + (Style.attrId -> AttrValue("dashed")))

    // Verify the updates are applied
    val updatedGraph3 = viewerState.fullGraph.now()
    assertEquals(
      updatedGraph3.getRootAttributes(AttributeTarget.edge).get(Style.attrId),
      Some(AttrValue("dashed")),
      "Root edge attributes should be updated"
    )
  }
