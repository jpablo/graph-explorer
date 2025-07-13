package org.jpablo.graphexplorer.viewer.state

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.formats.dot.ast.{AttrValue, AttributeTarget}
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Color, Shape, Style}
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.{AttributeId, ElementIds}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers

import scala.concurrent.ExecutionContext.Implicits.global

class ViewerStateSpec extends FunSuite with TestHelpers:
  // Graphviz adds a default directed attribute
  val minimalWithDirected =
    ViewerGraph.minimal.modify(_.elements.graphAttributes).using(_ + (AttributeId("directed") -> AttrValue("true")))

  test("addNodeWithSmartConnection should add a node to the graph"):
    withGraphviz { graphviz =>
      val viewerState = ViewerState(ProjectId("test"), graphviz, _ => ())
      // sanity check
      assertEquals(viewerState.fullGraphNow(), minimalWithDirected)
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
      assertEquals(viewerState.fullGraphNow(), minimalWithDirected)

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
      assertEquals(viewerState.fullGraphNow(), minimalWithDirected)

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

  test("rootTargetAttributesUpdates should update root attributes for the specified target"):
    withGraphviz { graphviz =>
      val viewerState = ViewerState(ProjectId("test"), graphviz, _ => ())

      // Initial state check
      assertEquals(viewerState.fullGraphNow(), minimalWithDirected)

      val graphUpdates = viewerState.defaultAttributesUpdates(AttributeTarget.graph)

      // Update graph attributes
      graphUpdates.update(_ + (Color.attrId -> AttrValue("blue")))

      // Verify the updates are applied
      val updatedGraph = viewerState.fullGraphNow()
      assertEquals(
        updatedGraph.getDefaultAttributes(AttributeTarget.graph).get(Color.attrId),
        Some(AttrValue("blue")),
        "Root graph attributes should be updated"
      )

      // Get the AttributesUpdates for node target
      val nodeUpdates = viewerState.defaultAttributesUpdates(AttributeTarget.node)

      // Update node attributes
      nodeUpdates.update(_ + (Shape.attrId -> AttrValue("box")))

      // Verify the updates are applied
      val updatedGraph2 = viewerState.fullGraphNow()
      assertEquals(
        updatedGraph2.getDefaultAttributes(AttributeTarget.node).get(Shape.attrId),
        Some(AttrValue("box")),
        "Root node attributes should be updated"
      )

      // Get the AttributesUpdates for edge target
      val edgeUpdates = viewerState.defaultAttributesUpdates(AttributeTarget.edge)

      // Update edge attributes
      edgeUpdates.update(_ + (Style.attrId -> AttrValue("dashed")))

      // Verify the updates are applied
      val updatedGraph3 = viewerState.fullGraphNow()
      assertEquals(
        updatedGraph3.getDefaultAttributes(AttributeTarget.edge).get(Style.attrId),
        Some(AttrValue("dashed")),
        "Root edge attributes should be updated"
      )
    }

  test("elementAttributes should update attributes for specific elements"):
    withGraphviz { graphviz =>
      val viewerState = ViewerState(ProjectId("test"), graphviz, _ => ())

      // Initial state check
      assertEquals(viewerState.fullGraphNow(), minimalWithDirected)

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
      arrowUpdates.update(_ + (Style.attrId -> AttrValue("dotted")))

      // Verify arrow attributes are updated
      val updatedGraph2 = viewerState.fullGraphNow()
      assertEquals(
        updatedGraph2.getAttributesById(arrowId).get(Style.attrId),
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
