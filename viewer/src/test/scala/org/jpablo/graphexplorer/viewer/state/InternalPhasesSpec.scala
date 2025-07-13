package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.ownership.Owner
import munit.FunSuite
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.AttributeId
import org.jpablo.graphexplorer.viewer.utils.TestHelpers

import scala.concurrent.ExecutionContext.Implicits.global

class InternalPhasesSpec extends FunSuite with TestHelpers:

  // Graphviz adds a default directed attribute
  val minimalWithDirected =
    ViewerGraph.minimal.modify(_.elements.graphAttributes).using(_ + (AttributeId("directed") -> AttrValue("true")))

  test("Sanity check"):
    withGraphviz { graphviz =>
      val viewerState = ViewerState(ProjectId("test"), graphviz, _ => ())
      given Owner     = viewerState.owner

      assertEquals(viewerState.phases.fullGraphV.now(), minimalWithDirected)

      val visibleDot =
        """|digraph "G" {
         |}""".stripMargin
      assertEquals(viewerState.visibleDOT.observe.now().value, visibleDot)
    }

  test("Updating the source text should update the graph"):
    withGraphviz { graphviz =>
      val viewerState = ViewerState(ProjectId("test"), graphviz, _ => ())

      val newSource =
        """|digraph "G" {
         |    "a" [label="A", other="value"];
         |}""".stripMargin

      viewerState.sourceText.set(newSource)

      val graph = viewerState.fullGraph.now()

      // Check that we have exactly one node with id "a"
      assertEquals(graph.nodes.size, 1, "Should have exactly one node")
      assert(graph.nodes.contains(org.jpablo.graphexplorer.viewer.models.NodeId("a")), "Should contain node 'a'")

      // Check the node attributes
      val nodeA = graph.nodes(org.jpablo.graphexplorer.viewer.models.NodeId("a"))
      assertEquals(nodeA.id.value, "a", "Node should have id 'a'")

      // Check attributes that should be preserved from the DOT parsing
      assertEquals(
        nodeA.attributes.get(org.jpablo.graphexplorer.viewer.models.AttributeId("label")).map(_.value),
        Some("A"),
        "Node should have label 'A'"
      )

      // Check that the node has the standard attributes added by the rendering process
      assert(nodeA.attributes.get(org.jpablo.graphexplorer.viewer.models.AttributeId("pos")).isDefined, "Node should have pos attribute")
      assert(
        nodeA.attributes.get(org.jpablo.graphexplorer.viewer.models.AttributeId("height")).isDefined,
        "Node should have height attribute"
      )
      assert(
        nodeA.attributes.get(org.jpablo.graphexplorer.viewer.models.AttributeId("width")).isDefined,
        "Node should have width attribute"
      )

      assertEquals(graph.groups.size, 0, "Should have no groups")
      assert(graph.arrows.isEmpty, "Should have no arrows")
      assert(graph.memberships.isEmpty, "Should have no memberships")
    }

  test("Updating the graph should trigger an update to the source text"):
    withGraphviz { graphviz =>
      val viewerState = ViewerState(ProjectId("test"), graphviz, _ => ())

      // Initial state check
      assertEquals(viewerState.sourceText.now(), PersistedDiagramState.minimalGraphText)

      val fullGraph = viewerState.fullGraph.now()
      assertEquals(fullGraph, minimalWithDirected)

      // Update the graph by adding a node
      viewerState.addNodeWithSmartConnection()

      // Verify that the source text was updated to reflect the new node
      val updatedGraph = viewerState.fullGraphNow()

      // The node count in the graph should match what we expect
      assertEquals(updatedGraph.nodes.size, 1, "Graph should have exactly one node")

      val graphId = updatedGraph.id
      val nodeId  = updatedGraph.nodeIds.head

      val expectedSource =
        s"""digraph "$graphId" {
           |  "$nodeId" [id="node:$nodeId", label="", pos="0,0", height="0.5", width="0.75"];
           |}""".stripMargin

      assertEquals(viewerState.sourceText.now(), expectedSource, "Source text should be updated to reflect the new node")
    }
