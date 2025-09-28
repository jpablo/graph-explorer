package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.ownership.Owner
import com.raquo.airstream.state.Val
import com.raquo.laminar.api.L.unsafeWindowOwner
import munit.FunSuite
import org.jpablo.graphexplorer.viewer.attributes.styleSubAttributes.StyleSubAttributes
import org.jpablo.graphexplorer.viewer.attributes.styleSubAttributes.StyleSubAttributes.fromExpandedAttributes
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{BorderStyle, CornerStyle, FillStyle, Style}
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.AttrStatus.Single
import org.jpablo.graphexplorer.viewer.models.{ElementIds, NodeId}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers

import scala.concurrent.ExecutionContext.Implicits.global

class InternalPhasesSpec extends FunSuite with TestHelpers:

  override def munitFixtures = List(mockStorageFixture())

  given Owner = unsafeWindowOwner

  test("Sanity check"):
    withGraphviz { graphviz =>
      val phases = new InternalPhases(graphviz, hiddenNodes = Val(ElementIds()))

      assertEquals(phases.fullGraphV.now(), ViewerGraph.minimal)

      val visibleDot =
        """|digraph "G" {
           |  node [
           |    sides="5",
           |    shape="box"
           |  ];
           |  edge [
           |    dir="both",
           |    arrowhead="vee",
           |    arrowtail="none"
           |  ];
           |}""".stripMargin
      assertNoDiff(phases.visibleDOT.observe.now().value, visibleDot)
    }

  test("Sanity check with initial source"):
    withGraphviz { graphviz =>
      // Simple DOT input for testing
      val simpleDot =
        """digraph "G" {
          |  graph [rankdir="LR"];
          |  "a" [label="a"];
          |  "b" [label="b"];
          |  "a" -> "b" [label="f"];
          |}""".stripMargin

      val phases = new InternalPhases(graphviz, initialSource = Some(simpleDot), hiddenNodes = Val(ElementIds()))

      val expected =
        """|digraph "G" {
           |  graph [rankdir="LR"];
           |  node [
           |    sides="5",
           |    shape="box"
           |  ];
           |  edge [
           |    dir="both",
           |    arrowhead="vee",
           |    arrowtail="none"
           |  ];
           |  "a" [
           |    id="node:a",
           |    label="a"
           |  ];
           |  "b" [
           |    id="node:b",
           |    label="b"
           |  ];
           |  "a" -> "b" [
           |    id="arrow:a->b/0",
           |    label="f"
           |  ];
           |}""".stripMargin

      assertNoDiff(phases.visibleDOT.observe.now().value, expected)
    }

  test("Complex node attributes should be preserved"):
    withGraphviz { graphviz =>
      // Simple DOT input for testing
      val simpleDot =
        """digraph "G" {
          |  "complexNode" [label="Complex Label", shape="ellipse", fontsize="12", width="1.5", height="0.8"];
          |}""".stripMargin

      val phases = new InternalPhases(graphviz, initialSource = Some(simpleDot), hiddenNodes = Val(ElementIds()))

      val expected =
        """|digraph "G" {
           |  node [
           |    sides="5",
           |    shape="box"
           |  ];
           |  edge [
           |    dir="both",
           |    arrowhead="vee",
           |    arrowtail="none"
           |  ];
           |  "complexNode" [
           |    id="node:complexNode",
           |    label="Complex Label",
           |    height="0.8",
           |    width="1.5",
           |    shape="ellipse",
           |    fontsize="12"
           |  ];
           |}""".stripMargin

      assertNoDiff(phases.visibleDOT.observe.now().value, expected)
    }

  test("Two groups should be handled correctly"):
    withGraphviz { graphviz =>
      // Simple DOT input for testing
      val simpleDot =
        """digraph "G" {
          |    subgraph G1 {
          |        graph [
          |            label="",
          |            cluster="true",
          |        ];
          |        "a" [label="a"];
          |    }
          |    subgraph G2 {
          |        graph [
          |            label="",
          |            cluster="true",
          |        ];
          |        "b" [label="b"];
          |    }
          |    "a" -> "b";
          |}""".stripMargin

      val phases = new InternalPhases(graphviz, initialSource = Some(simpleDot), hiddenNodes = Val(ElementIds()))

      val expected =
        """|digraph "G" {
           |  graph [label=""];
           |  node [
           |    sides="5",
           |    shape="box"
           |  ];
           |  edge [
           |    dir="both",
           |    arrowhead="vee",
           |    arrowtail="none"
           |  ];
           |  subgraph "G1" {
           |    graph [
           |      id="group:G1",
           |      label="",
           |      cluster="true"
           |    ];
           |    "a" [
           |      id="node:a",
           |      label="a"
           |    ];
           |  }
           |  subgraph "G2" {
           |    graph [
           |      id="group:G2",
           |      label="",
           |      cluster="true"
           |    ];
           |    "b" [
           |      id="node:b",
           |      label="b"
           |    ];
           |  }
           |  "a" -> "b" [id="arrow:a->b/0"];
           |}
           |""".stripMargin
      assertNoDiff(phases.visibleDOT.observe.now().value, expected)
    }

  test("Nested groups should be handled correctly"):
    withGraphviz { graphviz =>
      // Simple DOT input for testing
      val simpleDot =
        """digraph "G" {
          |    subgraph G1 {
          |        graph [
          |            label="group 1",
          |            cluster="true",
          |        ];
          |        subgraph G2 {
          |            graph [
          |                label="group 2",
          |                cluster="true",
          |            ];
          |            "c" [label="c"];
          |        }
          |        subgraph G3 {
          |            graph [
          |                label="group 3",
          |                cluster="true",
          |            ];
          |            "b" [
          |                label="b"
          |            ];
          |        }
          |    }
          |    "a" [
          |        label="a",
          |    ];
          |    "a" -> "b";
          |    "b" -> "c";
          |}""".stripMargin

      val phases = new InternalPhases(graphviz, initialSource = Some(simpleDot), hiddenNodes = Val(ElementIds()))

      val expected =
        """|digraph "G" {
           |    graph [label=""];
           |    node [
           |        sides="5",
           |        shape="box"
           |    ];
           |    edge [
           |        dir="both",
           |        arrowhead="vee",
           |        arrowtail="none"
           |    ];
           |    subgraph "G1" {
           |        graph [
           |            id="group:G1",
           |            label="group 1",
           |            cluster="true"
           |        ];
           |        subgraph "G2" {
           |            graph [
           |                id="group:G2",
           |                label="group 2",
           |                cluster="true"
           |            ];
           |            "c" [
           |                id="node:c",
           |                label="c"
           |            ];
           |        }
           |        subgraph "G3" {
           |            graph [
           |                id="group:G3",
           |                label="group 3",
           |                cluster="true"
           |            ];
           |            "b" [
           |                id="node:b",
           |                label="b"
           |            ];
           |        }
           |    }
           |    "a" [
           |        id="node:a",
           |        label="a"
           |    ];
           |    "a" -> "b" [
           |        id="arrow:a->b/1"
           |    ];
           |    "b" -> "c" [
           |        id="arrow:b->c/0"
           |    ];
           |}
           |""".stripMargin

      assertNoDiff(phases.visibleDOT.observe.now().value, expected)
    }

  test("Default styles should be handled correctly"):
    given Owner = unsafeWindowOwner

    withGraphviz { graphviz =>
      val simpleDot =
        """|digraph G {
           |    // 1. Establish a NEW global default for all nodes.
           |    // By default, every node should now be filled and rounded.
           |    node [style="filled,rounded", shape=box, fillcolor=lightblue];
           |
           |    // 2. Node 'n1' has its 'style' attribute MISSING.
           |    // It will INHERIT the new default we just set.
           |    n1 [label="n1: Inherits Default"];
           |
           |    // 3. Node 'n2' has 'style=""'.
           |    // This is an ACTIVE RESET command. It will IGNORE the 'filled,rounded'
           |    // default and revert to a node's most primitive form.
           |    n2 [label="n2: Resets to Primitive", style=""];
           |
           |    // 4. Node 'n3' has 'style="solid"'.
           |    // This is often thought of as "the default style". Let's see.
           |    // This explicitly sets the style, overriding the 'filled,rounded' default.
           |    n3 [label="n3: Explicitly 'solid'", style="solid"];
           |
           |    n1 -> n2 -> n3;
           |}
           |""".stripMargin

      val phases = new InternalPhases(graphviz, initialSource = Some(simpleDot), hiddenNodes = Val(ElementIds()))

      val simpleGraph = phases.simpleGraph.observe.now()

      // Sanity check: default styles should be applied correctly (flattened)
      assertEquals(simpleGraph.nodes(0).style, Some("filled,rounded"))
      assertEquals(simpleGraph.nodes(1).style, None) // Reset to primitive
      assertEquals(simpleGraph.nodes(2).style, Some("solid"))

      val fullGraph = phases.fullGraphV.now()

      assertEquals(fullGraph.nodes.size, 3, "Should have 3 nodes in the full graph")
      // No Style attribute should be present in the full graph,
      for node <- fullGraph.nodes.values do
        assertEquals(node.attributes.get(Style), None)

      // style should be expanded into sub-attributes and defaults reconstructed
      val attributes1 = fullGraph.nodes(NodeId("n1")).attributes

      assertEquals(attributes1.get(FillStyle).map(_.isTrue), Some(true))
      assertEquals(attributes1.get(CornerStyle).map(_.toString), Some("rounded"))
      assertEquals(
        fromExpandedAttributes(attributes1),
        StyleSubAttributes(fill = Single(true), corner = Single(CornerStyle.rounded))
      )

      val attributes2 = fullGraph.nodes(NodeId("n2")).attributes
      assertEquals(fromExpandedAttributes(attributes2), StyleSubAttributes.missing)

      val attributes3 = fullGraph.nodes(NodeId("n3")).attributes
      assertEquals(attributes3.get(BorderStyle).map(_.toString), Some("solid"))
      assertEquals(attributes3.get(FillStyle), None)
      assertEquals(attributes3.get(CornerStyle), None)
      assertEquals(
        fromExpandedAttributes(attributes3),
        StyleSubAttributes(border = Single(BorderStyle.solid))
      )

      val expected =
        """|digraph "G" {
           |  node [
           |    sides="5",
           |    shape="box"
           |  ];
           |  edge [
           |    dir="both",
           |    arrowhead="vee",
           |    arrowtail="none"
           |  ];
           |  "n1" [
           |    id="node:n1",
           |    label="n1: Inherits Default",
           |    shape="box",
           |    fillcolor="lightblue",
           |    style="filled,rounded"
           |  ];
           |  "n2" [
           |    id="node:n2",
           |    label="n2: Resets to Primitive",
           |    shape="box"
           |  ];
           |  "n3" [
           |    id="node:n3",
           |    label="n3: Explicitly 'solid'",
           |    shape="box",
           |    style=""
           |  ];
           |  "n1" -> "n2" [id="arrow:n1->n2/0"];
           |  "n2" -> "n3" [id="arrow:n2->n3/1"];
           |}""".stripMargin

      assertNoDiff(phases.visibleDOT.observe.now().value, expected)
    }

  test("Updating the source text should update the graph"):
    withGraphviz { graphviz =>
      val phases = new InternalPhases(graphviz, hiddenNodes = Val(ElementIds()))

      val newSource =
        """|digraph "G" {
           |    "a" [label="A", other="value"];
           |}""".stripMargin

      phases.sourceText.set(newSource)

      val graph = phases.fullGraphV.now()

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
      assertEquals(graph.groups.size, 0, "Should have no groups")
      assert(graph.arrows.isEmpty, "Should have no arrows")
      assert(graph.memberships.isEmpty, "Should have no memberships")
    }

  test("Updating the graph should trigger an update to the source text"):
    withGraphviz { graphviz =>
      val viewerState = ViewerState(ProjectId("test"), graphviz, _ => ())

      // Initial state check
      assertEquals(viewerState.sourceText.now(), PersistedDiagramState.minimalGraphText)

      val fullGraph = viewerState.fullGraphNow()
      assertEquals(fullGraph, ViewerGraph.minimal)

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
           |  "$nodeId" [label=""];
           |}""".stripMargin

      assertEquals(viewerState.sourceText.now(), expectedSource, "Source text should be updated to reflect the new node")
    }
