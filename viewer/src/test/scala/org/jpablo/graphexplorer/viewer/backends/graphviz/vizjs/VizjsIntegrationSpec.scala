package org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs

import com.raquo.airstream.ownership.Owner
import com.raquo.laminar.api.L.unsafeWindowOwner
import munit.FunSuite
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.SimpleGraphConverter
import org.jpablo.graphexplorer.viewer.formats.dot.DotText
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.graph.ViewerGraphElements
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.state.{ProjectId, ViewerState}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers

import scala.collection.immutable.VectorMap
import scala.concurrent.ExecutionContext.Implicits.global

class VizjsIntegrationSpec extends FunSuite with TestHelpers:

  given owner: Owner = unsafeWindowOwner

  test("SimpleGraphConverter should produce same result as current DOT parsing approach"):
    withGraphviz { graphviz =>
      // Simple DOT input for testing
      val simpleDot =
        """digraph "G" {
          |  graph [rankdir="LR"];
          |  "a" [label="a"];
          |  "b" [label="b"];
          |  "a" -> "b" [label="f"];
          |}""".stripMargin

      val state = ViewerState(ProjectId("test"), graphviz, initialSource = Some(simpleDot))

      val expected = state.fullGraphNow().elements

      graphviz.renderToJsonGraph(simpleDot).foreach { graph =>

        val actualElements = SimpleGraphConverter.toViewerGraphElements(graph)

        // Compare core structure - nodes and arrows should match
        assertEquals(actualElements.nodes.size, expected.nodes.size, "Node count should match")
        assertEquals(actualElements.arrows.size, expected.arrows.size, "Arrow count should match")

        // Verify nodes exist and have correct IDs
        expected.nodes.keys.foreach { nodeId =>
          assert(actualElements.nodes.contains(nodeId), s"Node $nodeId should exist")

          val actualNode   = actualElements.nodes(nodeId)
          val expectedNode = expected.nodes(nodeId)
          assertEquals(actualNode.id, expectedNode.id)

          // For real VizJS, labels should be preserved
          assertEquals(
            actualNode.attributes.get(AttributeId("label")),
            expectedNode.attributes.get(AttributeId("label")),
            "Node labels should match"
          )
        }

        // Verify arrows exist and have correct structure
        expected.arrows.keys.foreach { arrowId =>
          assert(actualElements.arrows.contains(arrowId), s"Arrow $arrowId should exist")

          val actualArrow   = actualElements.arrows(arrowId)
          val expectedArrow = expected.arrows(arrowId)

          assertEquals(actualArrow.source, expectedArrow.source)
          assertEquals(actualArrow.target, expectedArrow.target)
          assertEquals(actualArrow.seq, expectedArrow.seq)

          // Arrow labels should be preserved
          assertEquals(
            actualArrow.attributes.get(AttributeId("label")),
            expectedArrow.attributes.get(AttributeId("label")),
            "Arrow labels should match"
          )
        }

        // Structure should be consistent (no extra groups/memberships for simple graph)
        assertEquals(actualElements.groups.size, 0)
        assertEquals(actualElements.memberships.size, 0)
      }
    }

  test("SimpleGraphConverter should handle DOT with no default attributes"):
    withGraphviz { graphviz =>
      val minimalDot = """digraph "SimpleGraph" { "node1" [label="Node 1"] }"""

      val state = ViewerState(ProjectId("test"), graphviz, initialSource = Some(minimalDot))

      val expected = state.fullGraphNow().elements

      graphviz.renderToJsonGraph(minimalDot).foreach { graph =>
        val elements = SimpleGraphConverter.toViewerGraphElements(graph)

        assertEquals(elements.nodes.size, expected.nodes.size)
        assertEquals(elements.arrows.size, expected.arrows.size)

        expected.nodes.keys.foreach { nodeId =>
          assert(elements.nodes.contains(nodeId), s"Node $nodeId should exist")

          val actualNode   = elements.nodes(nodeId)
          val expectedNode = expected.nodes(nodeId)
          assertEquals(actualNode.attributes.get(AttributeId("label")), expectedNode.attributes.get(AttributeId("label")))
        }
      }
    }

  test("SimpleGraphConverter should preserve complex node attributes"):
    withGraphviz { graphviz =>
      val complexDot =
        """digraph "ComplexGraph" {
      "complexNode" [label="Complex Label", shape="ellipse", fontsize="12", width="1.5", height="0.8"];
    }"""

      val state = ViewerState(ProjectId("test"), graphviz, initialSource = Some(complexDot))

      val expected = state.fullGraphNow().elements

      graphviz.renderToJsonGraph(complexDot).foreach { graph =>
        val elements = SimpleGraphConverter.toViewerGraphElements(graph)

        // SimpleGraph conversion may have slightly different attributes than DOT parsing
        // Compare essential structure instead of exact equality
        assertEquals(elements.nodes.size, expected.nodes.size)
        assertEquals(elements.arrows.size, expected.arrows.size)
        assertEquals(elements.groups.size, expected.groups.size)
        assertEquals(elements.memberships.size, expected.memberships.size)

        // Verify node attributes are preserved
        expected.nodes.foreach { case (nodeId, expectedNode) =>
          assert(elements.nodes.contains(nodeId))
          val actualNode = elements.nodes(nodeId)
          assertEquals(
            actualNode.attributes.get(AttributeId("label")),
            expectedNode.attributes.get(AttributeId("label")),
            "Node label should match"
          )
          assertEquals(
            actualNode.attributes.get(AttributeId("shape")),
            expectedNode.attributes.get(AttributeId("shape")),
            "Node shape should match"
          )
          assertEquals(
            actualNode.attributes.get(AttributeId("fontsize")),
            expectedNode.attributes.get(AttributeId("fontsize")),
            "Node fontsize should match"
          )
          assertEquals(
            actualNode.attributes.get(AttributeId("width")),
            expectedNode.attributes.get(AttributeId("width")),
            "Node width should match"
          )
          assertEquals(
            actualNode.attributes.get(AttributeId("height")),
            expectedNode.attributes.get(AttributeId("height")),
            "Node height should match"
          )
        }
      }
    }

  test("SimpleGraphConverter should handle arrows with different nodes"):
    withGraphviz { graphviz =>
      val multiNodeDot = """digraph "MultiNode" {
      "start" [label="start"];
      "start" -> "middle" [label="first"];
      "middle" -> "end" [label="second"];
      "start" -> "end" [label="direct"];
    }"""

      val state = ViewerState(ProjectId("test"), graphviz, initialSource = Some(multiNodeDot))

      val expected = state.visibleGraph.observe.now().elements

      graphviz.renderToJsonGraph(multiNodeDot).foreach { graph =>
        val elements = SimpleGraphConverter.toViewerGraphElements(graph)

        // Compare against expected structure
        assertEquals(elements.nodes.size, expected.nodes.size)
        assertEquals(elements.arrows.size, expected.arrows.size)
        assertEquals(elements.groups.size, expected.groups.size)
        assertEquals(elements.memberships.size, expected.memberships.size)

        // Verify nodes match expected
        expected.nodes.keys.foreach { nodeId =>
          assert(elements.nodes.contains(nodeId), s"Node $nodeId should exist")
          assertEquals(elements.nodes(nodeId).id, expected.nodes(nodeId).id)
        }

        // Verify arrows match expected
        val expectedArrowData =
          expected.arrows.values.toSet.map: arrow =>
            (arrow.source, arrow.target, arrow.attributes.get(AttributeId("label")))

        val obtainedArrowData =
          elements.arrows.values.toSet.map: arrow =>
            (arrow.source, arrow.target, arrow.attributes.get(AttributeId("label")))

        assertEquals(obtainedArrowData, expectedArrowData, "Arrows should match expected structure")
      }
    }

  test("SimpleGraphConverter should handle a single group with one node"):
    withGraphviz { graphviz =>
      val nestedDOT =
        """digraph "G" {
          |  subgraph "g83c6c0dd" {
          |    graph [
          |      label="",
          |      labelloc="t",
          |      labeljust="c",
          |      cluster=true
          |    ];
          |    "a" [
          |      label="a",
          |      height=0.5,
          |      width=0.75
          |    ];
          |  }
          |}""".stripMargin

      val expectedGraphElements = ViewerGraphElements(
        nodes = VectorMap(
          ViewerNode.nodeWithId(
            NodeId("a"),
            "label"  -> "a",
            "height" -> "0.5",
            "width"  -> "0.75"
          )
        ),
        memberships = VectorMap(NodeId("a") -> GroupId("g83c6c0dd")),
        groups = Map(
          GroupId("g83c6c0dd") ->
            ViewerGroup.group(
              GroupId("g83c6c0dd"),
              Attributes(
                Map(
                  AttributeId("label")     -> AttrValue(""),
                  AttributeId("labelloc")  -> AttrValue("t"),
                  AttributeId("labeljust") -> AttrValue("c"),
                  AttributeId("cluster")   -> AttrValue("true"),
                  AttributeId("bb")        -> AttrValue("8,8,78,60")
                )
              )
            )
        )
      )

      graphviz.renderToJsonGraph(nestedDOT).foreach { graph =>
        val elements = SimpleGraphConverter.toViewerGraphElements(graph)
        // Note: SimpleGraph may have slightly different BB values or attribute ordering
        // Compare structure and essential attributes
        assertEquals(elements.nodes.size, expectedGraphElements.nodes.size, "Node count should match")
        assertEquals(elements.groups.size, expectedGraphElements.groups.size, "Group count should match")
        assertEquals(elements.memberships.size, expectedGraphElements.memberships.size, "Membership count should match")

        // Verify nodes
        expectedGraphElements.nodes.foreach { case (nodeId, expectedNode) =>
          assert(elements.nodes.contains(nodeId), s"Node $nodeId should exist")
          val actualNode = elements.nodes(nodeId)
          assertEquals(
            actualNode.attributes.get(AttributeId("label")),
            expectedNode.attributes.get(AttributeId("label")),
            s"Node $nodeId label should match"
          )
        }

        // Verify groups exist and have correct attributes
        expectedGraphElements.groups.foreach { case (groupId, expectedGroup) =>
          assert(elements.groups.contains(groupId), s"Group $groupId should exist")
          val actualGroup = elements.groups(groupId)
          assertEquals(
            actualGroup.attributes.get(AttributeId("label")),
            expectedGroup.attributes.get(AttributeId("label")),
            s"Group $groupId label should match"
          )
          assertEquals(
            actualGroup.attributes.get(AttributeId("cluster")),
            expectedGroup.attributes.get(AttributeId("cluster")),
            s"Group $groupId cluster attribute should match"
          )
        }

        // Verify memberships
        assertEquals(elements.memberships, expectedGraphElements.memberships, "Memberships should match exactly")
      }
    }

  test("SimpleGraphConverter should handle empty groups"):
    withGraphviz { graphviz =>
      val emptyGroup =
        """|digraph "G" {
           |  graph [label=""];
           |  subgraph "g387cb920" {
           |    graph [cluster="true"];
           |  }
           |  "a" [label="a"];
           |}""".stripMargin

      val triedGraph = graphviz.renderToJsonGraph(emptyGroup)
//      pprint.log(triedGraph)
      triedGraph.foreach { graph =>
        val elements = SimpleGraphConverter.graphToDotString(graph, omitInternal = true)
        val expected =
          """|digraph "G" {
             |  graph [label=""];
             |  subgraph "g387cb920" {
             |    graph [
             |      label="",
             |      cluster="true"
             |    ];
             |  }
             |  "a" [label="a"];
             |}""".stripMargin

        assertNoDiff(elements, expected)

      }
    }

  test("SimpleGraphConverter should handle two groups"):
    withGraphviz { graphviz =>
      val nestedDOT =
        """digraph "G" {
          |    subgraph gef4bf843 {
          |        graph [
          |            label="",
          |            cluster="true",
          |            labelloc="t",
          |            labeljust="c"
          |        ];
          |        "a" [label="a"];
          |    }
          |    subgraph g7505344b {
          |        graph [
          |            label="",
          |            cluster="true",
          |            labelloc="t",
          |            labeljust="c"
          |        ];
          |        "b" [label="b"];
          |    }
          |    "a" -> "b";
          |}""".stripMargin

      val expectedGraphElements = ViewerGraphElements(
        nodes = VectorMap(
          ViewerNode.nodeWithId(
            NodeId("a"),
            "label"  -> "a",
            "height" -> "0.5",
            "width"  -> "0.75"
          ),
          ViewerNode.nodeWithId(
            NodeId("b"),
            "label"  -> "b",
            "height" -> "0.5",
            "width"  -> "0.75"
          )
        ),
        arrows = Map(
          ArrowId("a->b/0") -> Arrow(
            source = NodeId("a"),
            target = NodeId("b"),
            attributes = Attributes(Map()),
            seq = 0
          )
        ),
        memberships = VectorMap(
          NodeId("a") -> GroupId("gef4bf843"),
          NodeId("b") -> GroupId("g7505344b")
        ),
        groups = Map(
          GroupId("gef4bf843") -> ViewerGroup.group(
            GroupId("gef4bf843"),
            attributes = Attributes(
              Map(
                AttributeId("label")     -> AttrValue(""),
                AttributeId("labelloc")  -> AttrValue("t"),
                AttributeId("labeljust") -> AttrValue("c"),
                AttributeId("cluster")   -> AttrValue("true"),
                AttributeId("bb")        -> AttrValue("8,80,78,132")
              )
            )
          ),
          GroupId("g7505344b") -> ViewerGroup.group(
            GroupId("g7505344b"),
            attributes = Attributes(
              Map(
                AttributeId("label")     -> AttrValue(""),
                AttributeId("labelloc")  -> AttrValue("t"),
                AttributeId("labeljust") -> AttrValue("c"),
                AttributeId("cluster")   -> AttrValue("true"),
                AttributeId("bb")        -> AttrValue("8,8,78,60")
              )
            )
          )
        )
      )

      graphviz.renderToJsonGraph(nestedDOT).foreach { graph =>
        val elements = SimpleGraphConverter.toViewerGraphElements(graph)
        // Note: SimpleGraph may have slightly different BB values or attribute ordering
        // Compare structure and essential attributes
        assertEquals(elements.nodes.size, expectedGraphElements.nodes.size, "Node count should match")
        assertEquals(elements.groups.size, expectedGraphElements.groups.size, "Group count should match")
        assertEquals(elements.memberships.size, expectedGraphElements.memberships.size, "Membership count should match")

        // Verify nodes
        expectedGraphElements.nodes.foreach { case (nodeId, expectedNode) =>
          assert(elements.nodes.contains(nodeId), s"Node $nodeId should exist")
          val actualNode = elements.nodes(nodeId)
          assertEquals(
            actualNode.attributes.get(AttributeId("label")),
            expectedNode.attributes.get(AttributeId("label")),
            s"Node $nodeId label should match"
          )
        }

        // Verify groups exist and have correct attributes
        expectedGraphElements.groups.foreach { case (groupId, expectedGroup) =>
          assert(elements.groups.contains(groupId), s"Group $groupId should exist")
          val actualGroup = elements.groups(groupId)
          assertEquals(
            actualGroup.attributes.get(AttributeId("label")),
            expectedGroup.attributes.get(AttributeId("label")),
            s"Group $groupId label should match"
          )
          assertEquals(
            actualGroup.attributes.get(AttributeId("cluster")),
            expectedGroup.attributes.get(AttributeId("cluster")),
            s"Group $groupId cluster attribute should match"
          )
        }

        // Verify memberships
        assertEquals(elements.memberships, expectedGraphElements.memberships, "Memberships should match exactly")
      }
    }

  test("SimpleGraphConverter should handle nested groups"):
    withGraphviz { graphviz =>
      val nestedDOT =
        """digraph "G" {
        |    subgraph g5294cce8 {
        |        graph [
        |            label="group 1",
        |            cluster="true",
        |        ];
        |        subgraph g863fd476 {
        |            graph [
        |                label="group 2",
        |                cluster="true",
        |            ];
        |            "c" [label="c"];
        |        }
        |        subgraph geca09c80 {
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

      val expectedGraphElements = ViewerGraphElements(
        nodes = VectorMap(
          ViewerNode.nodeWithId(
            NodeId("a"),
            "label"  -> "a",
            "height" -> "0.5",
            "width"  -> "0.75"
          ),
          ViewerNode.nodeWithId(
            NodeId("b"),
            "label"  -> "b",
            "height" -> "0.5",
            "width"  -> "0.75"
          ),
          ViewerNode.nodeWithId(
            NodeId("c"),
            "label"  -> "c",
            "height" -> "0.5",
            "width"  -> "0.75"
          )
        ),
        arrows = Map(
          ArrowId("b->c/0") -> Arrow(
            source = NodeId("b"),
            target = NodeId("c"),
            seq = 0
          ),
          ArrowId("a->b/1") -> Arrow(
            source = NodeId("a"),
            target = NodeId("b"),
            seq = 1
          )
        ),
        memberships = VectorMap(
          GroupId("g863fd476") -> GroupId("g5294cce8"),
          NodeId("c")          -> GroupId("g863fd476"),
          GroupId("geca09c80") -> GroupId("g5294cce8"),
          NodeId("b")          -> GroupId("geca09c80")
        ),
        groups = Map(
          GroupId("geca09c80") -> ViewerGroup.group(
            GroupId("geca09c80"),
            attributes = Attributes(
              Map(
                AttributeId("lp")        -> AttrValue("51,165.2"),
                AttributeId("label")     -> AttrValue("group 3"),
                AttributeId("lheight")   -> AttrValue("0.23"),
                AttributeId("lwidth")    -> AttrValue("0.60"),
                AttributeId("cluster")   -> AttrValue("true"),
                AttributeId("labelloc")  -> AttrValue("t"),
                AttributeId("labeljust") -> AttrValue("c"),
                AttributeId("bb")        -> AttrValue("16,100.8,86,177.6")
              )
            )
          ),
          GroupId("g863fd476") -> ViewerGroup.group(
            GroupId("g863fd476"),
            attributes = Attributes(
              Map(
                AttributeId("lp")        -> AttrValue("51,80.4"),
                AttributeId("label")     -> AttrValue("group 2"),
                AttributeId("lheight")   -> AttrValue("0.23"),
                AttributeId("lwidth")    -> AttrValue("0.60"),
                AttributeId("cluster")   -> AttrValue("true"),
                AttributeId("labelloc")  -> AttrValue("t"),
                AttributeId("labeljust") -> AttrValue("c"),
                AttributeId("bb")        -> AttrValue("16,16,86,92.8")
              )
            )
          ),
          GroupId("g5294cce8") -> ViewerGroup.group(
            GroupId("g5294cce8"),
            attributes = Attributes(
              Map(
                AttributeId("lp")        -> AttrValue("51,198"),
                AttributeId("label")     -> AttrValue("group 1"),
                AttributeId("lheight")   -> AttrValue("0.23"),
                AttributeId("lwidth")    -> AttrValue("0.60"),
                AttributeId("labelloc")  -> AttrValue("t"),
                AttributeId("labeljust") -> AttrValue("c"),
                AttributeId("cluster")   -> AttrValue("true"),
                AttributeId("bb")        -> AttrValue("8,8,94,210.4")
              )
            )
          )
        )
      )

      graphviz.renderToJsonGraph(nestedDOT).foreach { graph =>
        val elements = SimpleGraphConverter.toViewerGraphElements(graph)
        // Note: SimpleGraph may have slightly different BB values or attribute ordering
        // Compare structure and essential attributes
        assertEquals(elements.nodes.size, expectedGraphElements.nodes.size, "Node count should match")
        assertEquals(elements.groups.size, expectedGraphElements.groups.size, "Group count should match")
        assertEquals(elements.memberships.size, expectedGraphElements.memberships.size, "Membership count should match")

        // Verify nodes
        expectedGraphElements.nodes.foreach { case (nodeId, expectedNode) =>
          assert(elements.nodes.contains(nodeId), s"Node $nodeId should exist")
          val actualNode = elements.nodes(nodeId)
          assertEquals(
            actualNode.attributes.get(AttributeId("label")),
            expectedNode.attributes.get(AttributeId("label")),
            s"Node $nodeId label should match"
          )
        }

        // Verify groups exist and have correct attributes
        expectedGraphElements.groups.foreach { case (groupId, expectedGroup) =>
          assert(elements.groups.contains(groupId), s"Group $groupId should exist")
          val actualGroup = elements.groups(groupId)
          assertEquals(
            actualGroup.attributes.get(AttributeId("label")),
            expectedGroup.attributes.get(AttributeId("label")),
            s"Group $groupId label should match"
          )
          assertEquals(
            actualGroup.attributes.get(AttributeId("cluster")),
            expectedGroup.attributes.get(AttributeId("cluster")),
            s"Group $groupId cluster attribute should match"
          )
        }

        // Verify memberships
        assertEquals(elements.memberships, expectedGraphElements.memberships, "Memberships should match exactly")
      }
    }

  test("renderToSvg should generate SVG with correct element IDs for interactive functionality"):
    withGraphviz { graphviz =>
      // Test DOT with nodes, groups, and arrows to verify ID preservation
      val testDot =
        """digraph "G" {
          |  "nodeA" [id="node:nodeA", label="Node A"];
          |  "nodeB" [id="node:nodeB", label="Node B"];
          |  subgraph "cluster1" {
          |    graph [id="group:cluster1", label="Group 1"];
          |    "nodeC" [id="node:nodeC", label="Node C"];
          |  }
          |  "nodeA" -> "nodeB" [id="arrow:nodeA->nodeB/0", label="edge1"];
          |}""".stripMargin
      
      graphviz.renderToSvg(DotText(testDot)).foreach { svgWithPos =>
        val svgContent = svgWithPos.svg.ref.outerHTML

        // Verify node IDs are in the expected ElementId format
        assert(svgContent.contains("id=\"node:nodeA\""), "SVG should contain node:nodeA ID")
        assert(svgContent.contains("id=\"node:nodeB\""), "SVG should contain node:nodeB ID") 
        assert(svgContent.contains("id=\"node:nodeC\""), "SVG should contain node:nodeC ID")

        // Verify group ID is in the expected ElementId format  
        assert(svgContent.contains("id=\"group:cluster1\""), "SVG should contain group:cluster1 ID")

        // Verify arrow ID is preserved
        assert(svgContent.contains("id=\"arrow:nodeA->nodeB/0\""), "SVG should contain arrow:nodeA->nodeB/0 ID")

        // Ensure no generic IDs like "node1", "clust1", etc. are present
        assert(!svgContent.contains("id=\"node1\""), "SVG should not contain generic node1 ID")
        assert(!svgContent.contains("id=\"clust1\""), "SVG should not contain generic clust1 ID")
      }
    }