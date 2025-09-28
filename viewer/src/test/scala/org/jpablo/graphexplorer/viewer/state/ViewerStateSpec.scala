package org.jpablo.graphexplorer.viewer.state

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{BorderStyle, Color, Shape}
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.ElementIds
import org.jpablo.graphexplorer.viewer.utils.TestHelpers

import scala.concurrent.ExecutionContext.Implicits.global

class ViewerStateSpec extends FunSuite with TestHelpers:

  override def munitFixtures = List(mockStorageFixture())

  test("addNodeWithSmartConnection should add a node to the graph"):
    withGraphviz { graphviz =>
      val viewerState = ViewerState(ProjectId("test"), graphviz, _ => ())
      // sanity check
      assertEquals(viewerState.fullGraphNow(), ViewerGraph.minimal)
      assertEquals(viewerState.selection.size(), 0)

      viewerState.addNodeWithSmartConnection()

      // After this the recently added node is selected, so
      assertEquals(viewerState.selection.size(), 1)

      // ---- verify ---
      assertEquals(viewerState.allNodeIds().size, 1)
      assertEquals(viewerState.allArrowIds().size, 0)
    }

  test("two consecutive addNodeWithSmartConnection should add two nodes and one arrow to the graph"):
    withGraphviz { graphviz =>
      val viewerState = ViewerState(ProjectId("test"), graphviz, _ => ())
      // Initial state check
      assertEquals(viewerState.fullGraphNow(), ViewerGraph.minimal)

      viewerState.addNodeWithSmartConnection()
      // new node added and is currently selected
      viewerState.addNodeWithSmartConnection()
      // new node added and an arrow between the selected node and the new node

      // ---- verify ---
      assertEquals(viewerState.allNodeIds().size, 2)
      assertEquals(viewerState.allArrowIds().size, 1)
    }

  test("addArrow should add an arrow to the graph"):
    withGraphviz { graphviz =>
      val viewerState = ViewerState(ProjectId("test"), graphviz, _ => ())

      // Initial state check
      assertEquals(viewerState.fullGraphNow(), ViewerGraph.minimal)

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

  test("elementAttributes should update attributes for specific elements"):
    withGraphviz { graphviz =>
      val viewerState = ViewerState(ProjectId("test"), graphviz, _ => ())

      // Initial state check
      assertEquals(viewerState.fullGraphNow(), ViewerGraph.minimal)

      // Add two nodes to the graph
      viewerState.addNodeWithSmartConnection()
      viewerState.selection.clear()
      viewerState.addNodeWithSmartConnection()

      val Seq(nodeA, nodeB) = viewerState.allNodeIds().toSeq

      // Add an arrow between the nodes
      viewerState.addArrow(nodeA, nodeB)
      val Seq(arrowId) = viewerState.allArrowIds().toSeq

      // Test updating node attributes
      val nodeUpdates = viewerState.elementAttributesUpdates(ElementIds.from(nodeA))
      nodeUpdates.update(_ + (Color.attrId -> AttrValue("red")))

      // Verify node attributes are updated
      val updatedGraph = viewerState.fullGraphNow()
      assertEquals(
        updatedGraph.getAttributesById(nodeA).get(Color.attrId),
        Some(AttrValue("red")),
        "Node attributes should be updated"
      )

      // Test updating arrow attributes
      val arrowUpdates = viewerState.elementAttributesUpdates(ElementIds.from(arrowId))
      arrowUpdates.update(_ + (BorderStyle.attrId -> AttrValue("dotted")))

      // Verify arrow attributes are updated
      val updatedGraph2 = viewerState.fullGraphNow()
      assertEquals(
        updatedGraph2.getAttributesById(arrowId).get(BorderStyle.attrId),
        Some(AttrValue("dotted")),
        "Arrow attributes should be updated"
      )

      // Test updating multiple elements at once
      val multiUpdates = viewerState.elementAttributesUpdates(ElementIds(Set(nodeA, nodeB)))
      multiUpdates.update(_ + (Shape.attrId -> AttrValue("box")))

      // Verify multiple elements are updated
      val updatedGraph3 = viewerState.fullGraphNow()
      assertEquals(
        updatedGraph3.getAttributesById(nodeA).get(Shape.attrId),
        Some(AttrValue("box")),
        "First node shape should be updated"
      )
      assertEquals(
        updatedGraph3.getAttributesById(nodeB).get(Shape.attrId),
        Some(AttrValue("box")),
        "Second node shape should be updated"
      )
    }
