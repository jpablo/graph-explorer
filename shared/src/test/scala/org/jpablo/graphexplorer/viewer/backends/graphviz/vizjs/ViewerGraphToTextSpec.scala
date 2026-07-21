package org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.SimpleGraph
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Label
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph.viewerGraphToText
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraphElements, viewerGraphElementsToText}
import org.jpablo.graphexplorer.viewer.models.*
import upickle.default.*

class ViewerGraphToTextSpec extends FunSuite:

  test("viewerGraphElementsToText should preserve edge IDs in DOT output") {
    import scala.collection.immutable.VectorMap

    // Create a simple graph with edges
    val nodeA  = NodeId("a")
    val nodeB  = NodeId("b")
    val arrow1 = Arrow(nodeA, nodeB, seq = 1)
    val arrow2 = Arrow(nodeA, nodeB, seq = 2)

    val elements = ViewerGraphElements(
      nodes = VectorMap(
        nodeA -> ViewerNode.nodeNoDefaults(nodeA, Attributes.empty),
        nodeB -> ViewerNode.nodeNoDefaults(nodeB, Attributes.empty)
      ),
      arrows = Map(arrow1.id -> arrow1, arrow2.id -> arrow2)
    )

    // Convert to DOT string
    val dotString = viewerGraphElementsToText(elements)

    // Verify that edge IDs are included in the DOT output with "arrow:" prefix
    assert(dotString.contains("""id="arrow:a->b/1""""), s"DOT should contain edge ID 'arrow:a->b/1', but got:\n$dotString")
    assert(dotString.contains("""id="arrow:a->b/2""""), s"DOT should contain edge ID 'arrow:a->b/2', but got:\n$dotString")

    // Verify the overall structure
    assert(dotString.contains(""""a" -> "b""""), "DOT should contain edge from a to b")
  }

  test("SimpleGraphConverter should include group IDs in DOT output") {
    import scala.collection.immutable.VectorMap

    // Create a graph with a group/cluster
    val nodeA   = NodeId("a")
    val groupId = GroupId("first_group")

    val elements = ViewerGraphElements(
      nodes = VectorMap(
        nodeA -> ViewerNode.nodeNoDefaults(
          nodeA,
          Attributes(VectorMap(
            Label.attrId  -> AttrValue("a"),
            AttributeId("pos")    -> AttrValue("43,58.8"),
            AttributeId("height") -> AttrValue("0.5"),
            AttributeId("width")  -> AttrValue("0.75")
          ))
        )
      ),
      arrows = Map.empty,
      memberships = VectorMap(nodeA -> groupId),
      groups = Map(
        groupId -> ViewerGroup.group(
          groupId,
          Attributes(VectorMap(
            Label.attrId   -> AttrValue("A title"),
            AttributeId("lheight") -> AttrValue("0.23"),
            AttributeId("lp")      -> AttrValue("43,97.2"),
            AttributeId("lwidth")  -> AttrValue("0.49"),
            AttributeId("cluster") -> AttrValue("true")
          ))
        )
      ),
      graphAttributes = Attributes(VectorMap(
        Label.attrId   -> AttrValue("A title"),
        AttributeId("rankdir") -> AttrValue("LR")
      ))
    )

    // Convert to DOT string
    val dotString = viewerGraphElementsToText(elements)

    val expected =
      """|digraph "G" {
         |  graph [
         |    label="A title",
         |    rankdir="LR"
         |  ];
         |  subgraph "cluster_first_group" {
         |    graph [
         |      id="group:first_group",
         |      lp="43,97.2",
         |      label="A title",
         |      lheight="0.23",
         |      lwidth="0.49",
         |      cluster="true"
         |    ];
         |    "a" [
         |      id="node:a",
         |      label="a",
         |      pos="43,58.8",
         |      height="0.5",
         |      width="0.75"
         |    ];
         |  }
         |}""".stripMargin

    assertNoDiff(dotString, expected)
  }

  test("groups with empty labels should have explicit empty label in DOT output") {
    import scala.collection.immutable.VectorMap

    // Create a graph with a group that has an empty label
    val nodeA   = NodeId("a")
    val groupId = GroupId("g9c2b161c")

    val elements = ViewerGraphElements(
      nodes = VectorMap(
        nodeA -> ViewerNode.nodeNoDefaults(nodeA, Attributes(VectorMap(Label.attrId -> AttrValue("a"))))
      ),
      arrows = Map.empty,
      memberships = VectorMap(nodeA -> groupId),
      groups = Map(
        groupId -> ViewerGroup.group(groupId, Attributes(VectorMap(Label.attrId -> AttrValue("")))) // Empty label
      ),
      graphAttributes = Attributes(VectorMap(
        Label.attrId   -> AttrValue("Diagram"),
        AttributeId("rankdir") -> AttrValue("LR")
      ))
    )

    // Convert to DOT string
    val dotString = viewerGraphElementsToText(elements)

    // Verify that the subgraph has an explicit empty label to prevent inheritance
    assert(dotString.contains("""label="""""), s"DOT should contain explicit empty label, but got:\n$dotString")

    // Verify the full structure contains the empty label
    val expected =
      """|digraph "G" {
         |  graph [
         |    label="Diagram",
         |    rankdir="LR"
         |  ];
         |  subgraph "cluster_g9c2b161c" {
         |    graph [
         |      id="group:g9c2b161c",
         |      label="",
         |      cluster="true"
         |    ];
         |    "a" [
         |      id="node:a",
         |      label="a"
         |    ];
         |  }
         |}""".stripMargin

    assertNoDiff(dotString, expected)
  }

  test("groups with non-empty labels should include their label in DOT output") {
    import scala.collection.immutable.VectorMap

    // Create a graph with a group that has a non-empty label
    val nodeA   = NodeId("a")
    val groupId = GroupId("g9c2b161c")

    val elements = ViewerGraphElements(
      nodes = VectorMap(
        nodeA -> ViewerNode.nodeNoDefaults(nodeA, Attributes(VectorMap(Label.attrId -> AttrValue("a"))))
      ),
      arrows = Map.empty,
      memberships = VectorMap(nodeA -> groupId),
      groups = Map(
        groupId -> ViewerGroup.group(groupId, Attributes(VectorMap(Label.attrId -> AttrValue("Custom Group Label"))))
      ),
      graphAttributes = Attributes(VectorMap(
        Label.attrId   -> AttrValue("Diagram"),
        AttributeId("rankdir") -> AttrValue("LR")
      ))
    )

    // Convert to DOT string
    val dotString = viewerGraphElementsToText(elements)

    // Verify that the subgraph has its custom label
    assert(dotString.contains("""label="Custom Group Label""""), s"DOT should contain custom label, but got:\n$dotString")

    // Verify the full structure
    val expected =
      """|digraph "G" {
         |  graph [
         |    label="Diagram",
         |    rankdir="LR"
         |  ];
         |  subgraph "cluster_g9c2b161c" {
         |    graph [
         |      id="group:g9c2b161c",
         |      label="Custom Group Label",
         |      cluster="true"
         |    ];
         |    "a" [
         |      id="node:a",
         |      label="a"
         |    ];
         |  }
         |}""".stripMargin

    assertNoDiff(dotString, expected)
  }

  test("nested groups should generate proper DOT hierarchy") {
    import scala.collection.immutable.VectorMap

    // Create a graph with nested groups: G1 contains innerGroup which contains node b
    val nodeB        = NodeId("b")
    val outerGroupId = GroupId("g0f6ceed0")
    val innerGroupId = GroupId("gacd87035")

    val elements = ViewerGraphElements(
      nodes = VectorMap(
        nodeB -> ViewerNode.nodeNoDefaults(
          nodeB,
          Attributes(VectorMap(
            Label.attrId -> AttrValue("b")
          ))
        )
      ),
      arrows = Map.empty,
      memberships = VectorMap(
        nodeB        -> innerGroupId, // b belongs to innerGroup
        innerGroupId -> outerGroupId  // innerGroup belongs to G1
      ),
      groups = Map(
        outerGroupId -> ViewerGroup.group(
          outerGroupId,
          Attributes(VectorMap(
            Label.attrId   -> AttrValue("G1"),
            AttributeId("cluster") -> AttrValue("true")
          ))
        ),
        innerGroupId -> ViewerGroup.group(
          innerGroupId,
          Attributes(VectorMap(
            Label.attrId   -> AttrValue(""),
            AttributeId("cluster") -> AttrValue("true")
          ))
        )
      ),
      graphAttributes = Attributes(VectorMap(
        Label.attrId   -> AttrValue(""),
        AttributeId("rankdir") -> AttrValue("LR")
      ))
    )

    // Convert to DOT string
    val dotString = viewerGraphElementsToText(elements)

    // Verify that innerGroup is nested inside G1, not a sibling
    val expected =
      """|digraph "G" {
         |    graph [
         |        label="",
         |        rankdir="LR"
         |    ];
         |    subgraph "cluster_g0f6ceed0" {
         |        graph [
         |            id="group:g0f6ceed0",
         |            label="G1",
         |            cluster="true"
         |        ];
         |        subgraph "cluster_gacd87035" {
         |            graph [
         |                id="group:gacd87035",
         |                label="",
         |                cluster="true"
         |            ];
         |            "b" [
         |                id="node:b",
         |                label="b"
         |            ];
         |        }
         |    }
         |}""".stripMargin

    assertNoDiff(dotString, expected)
  }

  test("graph should preserve layout and start attributes as top-level attributes") {
    // Create a SimpleGraph with layout and start attributes at the top level
    val simpleGraphJson = """{
      "name": "TestGraph",
      "directed": true,
      "layout": "neato",
      "start": "42",
      "objects": [
        {
          "_gvid": 0,
          "name": "node1",
          "label": "Node 1"
        }
      ]
    }"""

    val simpleGraph    = read[SimpleGraph](simpleGraphJson)
    val viewerElements = simplegraph.toViewerGraph(simpleGraph).elements

    // Check that the graph attributes include layout and start
    val layoutAttr = viewerElements.graphAttributes.values.get(AttributeId("layout"))
    val startAttr  = viewerElements.graphAttributes.values.get(AttributeId("start"))

    assertEquals(layoutAttr, Some(AttrValue("neato")), "Layout attribute should be preserved in graph attributes")
    assertEquals(startAttr, Some(AttrValue("42")), "Start attribute should be preserved in graph attributes")

    // Convert back to DOT and check if attributes are preserved
    val dotString = viewerGraphElementsToText(viewerElements)

    assert(dotString.contains("layout=\"neato\""), s"DOT should contain layout attribute, but got:\n$dotString")
    assert(dotString.contains("start=\"42\""), s"DOT should contain start attribute, but got:\n$dotString")
  }

  test("nested groups should be handled correctly with subgraphs") {

    val json =
      """|{
         |  "name": "G",
         |  "directed": true,
         |  "label": "",
         |  "objects": [
         |    {"name": "G1", "cluster": "true", "label": "group 1", "_gvid": 0, "subgraphs": [1], "nodes": [2]},
         |    {"name": "G2", "cluster": "true", "label": "group 2", "_gvid": 1, "nodes": [2]},
         |    {"_gvid": 2, "name": "c", "label": "c", "shape": "circle"},
         |    {"_gvid": 3, "name": "b", "label": "\\N", "shape": "circle"}
         |  ],
         |  "edges": [
         |    {"_gvid": 0, "tail": 3, "head": 2, "style": "dashed"}
         |  ]
         |}""".stripMargin

    val simpleGraph  = read[SimpleGraph](json)
    val visibleGraph = simplegraph.toViewerGraph(simpleGraph).toVisibleGraph(ElementIds())

    val dotString = viewerGraphToText(visibleGraph, false)

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
         |    subgraph "cluster_G1" {
         |        graph [
         |            id="group:G1",
         |            label="group 1",
         |            cluster="true"
         |        ];
         |        subgraph "cluster_G2" {
         |            graph [
         |                id="group:G2",
         |                label="group 2",
         |                cluster="true"
         |            ];
         |            "c" [
         |                id="node:c",
         |                label="c",
         |                shape="circle"
         |            ];
         |        }
         |    }
         |    "b" [
         |        id="node:b",
         |        shape="circle"
         |    ];
         |    "b" -> "c" [
         |        id="arrow:b->c/0",
         |        style="dashed"
         |    ];
         |}""".stripMargin

    assertNoDiff(dotString, expected)
  }

  test("side by side groups should be handled correctly") {
    // Create a SimpleGraph with two side-by-side groups
    val json =
      """|{
         |    "name": "G",
         |    "directed": true,
         |    "strict": false,
         |    "bb": "0,0,216,401.01",
         |    "fontname": "Helvetica,Arial,sans-serif",
         |    "label": "",
         |    "_subgraph_cnt": 2,
         |    "objects": [
         |        {
         |            "name": "cluster_0",
         |            "bb": "8,64.214,98,357.01",
         |            "color": "lightgrey",
         |            "fontname": "Helvetica,Arial,sans-serif",
         |            "label": "process #1",
         |            "labeljust": "c",
         |            "labelloc": "t",
         |            "lheight": "0.23",
         |            "lp": "53,344.61",
         |            "lwidth": "0.83",
         |            "style": "filled",
         |            "_gvid": 0,
         |            "nodes": [
         |                2,
         |                3,
         |                4,
         |                5
         |            ],
         |            "edges": [
         |                0,
         |                4,
         |                3,
         |                1
         |            ]
         |        },
         |        {
         |            "name": "cluster_1",
         |            "bb": "133,64.214,208,357.01",
         |            "color": "blue",
         |            "fontname": "Helvetica,Arial,sans-serif",
         |            "label": "process #2",
         |            "labeljust": "c",
         |            "labelloc": "t",
         |            "lheight": "0.23",
         |            "lp": "170.5,344.61",
         |            "lwidth": "0.83",
         |            "_gvid": 1,
         |            "nodes": [
         |                6,
         |                7,
         |                8,
         |                9
         |            ],
         |            "edges": [
         |                9,
         |                7,
         |                6
         |            ]
         |        },
         |        {
         |            "_gvid": 2,
         |            "name": "a0",
         |            "color": "white",
         |            "fontname": "Helvetica,Arial,sans-serif",
         |            "height": "0.5",
         |            "label": "\\N",
         |            "pos": "63,306.21",
         |            "shape": "ellipse",
         |            "style": "filled",
         |            "width": "0.75"
         |        },
         |        {
         |            "_gvid": 3,
         |            "name": "a1",
         |            "color": "white",
         |            "fontname": "Helvetica,Arial,sans-serif",
         |            "height": "0.5",
         |            "label": "\\N",
         |            "pos": "63,234.21",
         |            "shape": "ellipse",
         |            "style": "filled",
         |            "width": "0.75"
         |        },
         |        {
         |            "_gvid": 4,
         |            "name": "a2",
         |            "color": "white",
         |            "fontname": "Helvetica,Arial,sans-serif",
         |            "height": "0.5",
         |            "label": "\\N",
         |            "pos": "63,162.21",
         |            "shape": "ellipse",
         |            "style": "filled",
         |            "width": "0.75"
         |        },
         |        {
         |            "_gvid": 5,
         |            "name": "a3",
         |            "color": "white",
         |            "fontname": "Helvetica,Arial,sans-serif",
         |            "height": "0.5",
         |            "label": "\\N",
         |            "pos": "63,90.214",
         |            "shape": "ellipse",
         |            "style": "filled",
         |            "width": "0.75"
         |        },
         |        {
         |            "_gvid": 6,
         |            "name": "b0",
         |            "fontname": "Helvetica,Arial,sans-serif",
         |            "height": "0.5",
         |            "label": "\\N",
         |            "pos": "168,306.21",
         |            "shape": "ellipse",
         |            "style": "filled",
         |            "width": "0.75"
         |        },
         |        {
         |            "_gvid": 7,
         |            "name": "b1",
         |            "fontname": "Helvetica,Arial,sans-serif",
         |            "height": "0.5",
         |            "label": "\\N",
         |            "pos": "170,234.21",
         |            "shape": "ellipse",
         |            "style": "filled",
         |            "width": "0.75"
         |        },
         |        {
         |            "_gvid": 8,
         |            "name": "b2",
         |            "fontname": "Helvetica,Arial,sans-serif",
         |            "height": "0.5",
         |            "label": "\\N",
         |            "pos": "173,162.21",
         |            "shape": "ellipse",
         |            "style": "filled",
         |            "width": "0.75"
         |        },
         |        {
         |            "_gvid": 9,
         |            "name": "b3",
         |            "fontname": "Helvetica,Arial,sans-serif",
         |            "height": "0.5",
         |            "label": "\\N",
         |            "pos": "168,90.214",
         |            "shape": "ellipse",
         |            "style": "filled",
         |            "width": "0.75"
         |        },
         |        {
         |            "_gvid": 10,
         |            "name": "start",
         |            "fontname": "Helvetica,Arial,sans-serif",
         |            "height": "0.5",
         |            "label": "\\N",
         |            "pos": "115,383.01",
         |            "shape": "Mdiamond",
         |            "width": "1.0867"
         |        },
         |        {
         |            "_gvid": 11,
         |            "name": "end",
         |            "fontname": "Helvetica,Arial,sans-serif",
         |            "height": "0.50297",
         |            "label": "\\N",
         |            "pos": "115,18.107",
         |            "shape": "Msquare",
         |            "width": "0.50297"
         |        }
         |    ],
         |    "edges": [
         |        {
         |            "_gvid": 9,
         |            "tail": 8,
         |            "head": 9,
         |            "fontname": "Helvetica,Arial,sans-serif",
         |            "pos": "e,169.22,108.32 171.76,143.91 171.24,136.62 170.62,127.94 170.04,119.75"
         |        },
         |        {
         |            "_gvid": 10,
         |            "tail": 9,
         |            "head": 11,
         |            "fontname": "Helvetica,Arial,sans-serif",
         |            "pos": "e,128.22,36.594 156.24,73.655 150.02,65.426 142.21,55.094 135.06,45.643"
         |        },
         |        {
         |            "_gvid": 7,
         |            "tail": 7,
         |            "head": 8,
         |            "fontname": "Helvetica,Arial,sans-serif",
         |            "pos": "e,172.27,180.32 170.74,215.91 171.05,208.62 171.43,199.94 171.78,191.75"
         |        },
         |        {
         |            "_gvid": 0,
         |            "tail": 2,
         |            "head": 3,
         |            "fontname": "Helvetica,Arial,sans-serif",
         |            "pos": "e,63,252.32 63,287.91 63,280.62 63,271.94 63,263.75"
         |        },
         |        {
         |            "_gvid": 4,
         |            "tail": 5,
         |            "head": 2,
         |            "fontname": "Helvetica,Arial,sans-serif",
         |            "pos": "e,49.25,290.28 49.25,106.15 41.039,116.11 31.381,129.97 27,144.21 12.892,190.09 12.892,206.33 27,252.21 30.183,262.57 36.152,272.71 42.327,281.31"
         |        },
         |        {
         |            "_gvid": 12,
         |            "tail": 10,
         |            "head": 6,
         |            "fontname": "Helvetica,Arial,sans-serif",
         |            "pos": "e,156.85,322.95 124.23,368.98 131.39,358.88 141.53,344.57 150.25,332.26"
         |        },
         |        {
         |            "_gvid": 2,
         |            "tail": 3,
         |            "head": 9,
         |            "fontname": "Helvetica,Arial,sans-serif",
         |            "pos": "e,156.63,106.59 74.638,217.47 92.867,192.82 128.3,144.91 149.88,115.71"
         |        },
         |        {
         |            "_gvid": 11,
         |            "tail": 10,
         |            "head": 2,
         |            "fontname": "Helvetica,Arial,sans-serif",
         |            "pos": "e,73.937,322.95 105.94,368.98 98.916,358.88 88.967,344.57 80.414,332.26"
         |        },
         |        {
         |            "_gvid": 3,
         |            "tail": 4,
         |            "head": 5,
         |            "fontname": "Helvetica,Arial,sans-serif",
         |            "pos": "e,63,108.32 63,143.91 63,136.62 63,127.94 63,119.75"
         |        },
         |        {
         |            "_gvid": 1,
         |            "tail": 3,
         |            "head": 4,
         |            "fontname": "Helvetica,Arial,sans-serif",
         |            "pos": "e,63,180.32 63,215.91 63,208.62 63,199.94 63,191.75"
         |        },
         |        {
         |            "_gvid": 6,
         |            "tail": 6,
         |            "head": 7,
         |            "fontname": "Helvetica,Arial,sans-serif",
         |            "pos": "e,169.51,252.32 168.49,287.91 168.7,280.62 168.95,271.94 169.18,263.75"
         |        },
         |        {
         |            "_gvid": 5,
         |            "tail": 5,
         |            "head": 11,
         |            "fontname": "Helvetica,Arial,sans-serif",
         |            "pos": "e,102.03,36.594 74.54,73.655 80.644,65.426 88.307,55.094 95.318,45.643"
         |        },
         |        {
         |            "_gvid": 8,
         |            "tail": 8,
         |            "head": 5,
         |            "fontname": "Helvetica,Arial,sans-serif",
         |            "pos": "e,81.941,103.27 153.84,149.02 136.65,138.08 111.18,121.87 91.58,109.4"
         |        }
         |    ]
         |}""".stripMargin

    val simpleGraph  = read[SimpleGraph](json)
    val visibleGraph = simplegraph.toViewerGraph(simpleGraph).toVisibleGraph(ElementIds())

    val dotString = viewerGraphElementsToText(visibleGraph.elements)

    // Both clusters should appear at the same level (not nested)
    assert(dotString.contains("subgraph \"cluster_0\""), "Should contain cluster_0 subgraph")
    assert(dotString.contains("subgraph \"cluster_1\""), "Should contain cluster_1 subgraph")

    // Verify both clusters have correct labels
    assert(dotString.contains("label=\"process #1\""), "Should contain process #1 label")
    assert(dotString.contains("label=\"process #2\""), "Should contain process #2 label")

    // Verify clusters are at the same level by checking indentation
    val lines        = dotString.split("\n")
    val cluster0Line = lines.find(_.contains("subgraph \"cluster_0\""))
    val cluster1Line = lines.find(_.contains("subgraph \"cluster_1\""))

    assert(cluster0Line.isDefined, "cluster_0 subgraph line should exist")
    assert(cluster1Line.isDefined, "cluster_1 subgraph line should exist")

    // Both should have the same indentation (2 spaces = top level)
    val cluster0Indent = cluster0Line.get.takeWhile(_ == ' ').length
    val cluster1Indent = cluster1Line.get.takeWhile(_ == ' ').length

    assertEquals(cluster0Indent, cluster1Indent, "Both clusters should have the same indentation level")
    assertEquals(cluster0Indent, 2, "Clusters should be at top level (2 spaces indentation)")

    // Verify nodes are properly nested inside their clusters
    assert(dotString.contains("\"a0\""), "Should contain node a0")
    assert(dotString.contains("\"b0\""), "Should contain node b0")

    // Verify both "start" and "end" nodes appear outside clusters
    assert(dotString.contains("\"start\""), "Should contain start node")
    assert(dotString.contains("\"end\""), "Should contain end node")

    // Verify that all expected nodes are present
    assert(dotString.contains("\"a0\""), "Should contain a0")
    assert(dotString.contains("\"a1\""), "Should contain a1")
    assert(dotString.contains("\"a2\""), "Should contain a2")
    assert(dotString.contains("\"a3\""), "Should contain a3")
    assert(dotString.contains("\"b0\""), "Should contain b0")
    assert(dotString.contains("\"b1\""), "Should contain b1")
    assert(dotString.contains("\"b2\""), "Should contain b2")
    assert(dotString.contains("\"b3\""), "Should contain b3")

    // Note: Node ordering within clusters should now be preserved by _gvid sorting

  }

  test("node order within clusters should be preserved") {
    // Create a simple test to verify that nodes within clusters maintain their original order
    val json =
      """|{
         |  "name": "G", 
         |  "directed": true,
         |  "objects": [
         |    {
         |      "name": "cluster_test",
         |      "_gvid": 0,
         |      "nodes": [5, 3, 7, 1]
         |    },
         |    {"_gvid": 1, "name": "node1", "label": "Node 1"},
         |    {"_gvid": 3, "name": "node2", "label": "Node 2"}, 
         |    {"_gvid": 5, "name": "node3", "label": "Node 3"},
         |    {"_gvid": 7, "name": "node4", "label": "Node 4"}
         |  ]
         |}""".stripMargin

    val simpleGraph  = read[SimpleGraph](json)
    val visibleGraph = simplegraph.toViewerGraph(simpleGraph).toVisibleGraph(ElementIds())
    val dotString    = viewerGraphElementsToText(visibleGraph.elements)

    // Verify nodes appear in order of their _gvid within the cluster: 1, 3, 5, 7
    val clusterContent = dotString.substring(
      dotString.indexOf("subgraph \"cluster_test\""),
      dotString.indexOf("}", dotString.indexOf("subgraph \"cluster_test\""))
    )

    val node1Pos = clusterContent.indexOf("\"node1\"")
    val node2Pos = clusterContent.indexOf("\"node2\"")
    val node3Pos = clusterContent.indexOf("\"node3\"")
    val node4Pos = clusterContent.indexOf("\"node4\"")

    assert(node1Pos >= 0, "node1 should be found in cluster")
    assert(node2Pos >= 0, "node2 should be found in cluster")
    assert(node3Pos >= 0, "node3 should be found in cluster")
    assert(node4Pos >= 0, "node4 should be found in cluster")

    assert(node1Pos < node2Pos, "node1 should appear before node2")
    assert(node2Pos < node3Pos, "node2 should appear before node3")
    assert(node3Pos < node4Pos, "node3 should appear before node4")
  }

  test("node order within clusters should be preserved - II") {
    // Create a simple test to verify that nodes within clusters maintain their original order
    val json =
      """|{
         |  "name": "G",
         |  "directed": true,
         |  "objects": [
         |    {
         |      "_gvid": 0,
         |      "name": "cluster_0",
         |      "nodes": [2, 3, 4, 5],
         |      "label": "process #1",
         |      "edges": [0, 4, 3, 1],
         |      "color": "lightgrey",
         |      "style": "filled"
         |    },
         |    {
         |      "_gvid": 1,
         |      "name": "cluster_1",
         |      "nodes": [6, 7, 8, 9],
         |      "label": "process #2",
         |      "edges": [9, 7, 6],
         |      "color": "blue"
         |    },
         |    {"_gvid": 2, "name": "a0", "label": "\\N", "shape": "ellipse", "color": "white", "style": "filled"},
         |    {"_gvid": 3, "name": "a1", "label": "\\N", "shape": "ellipse", "color": "white", "style": "filled"},
         |    {"_gvid": 4, "name": "a2", "label": "\\N", "shape": "ellipse", "color": "white", "style": "filled"},
         |    {"_gvid": 5, "name": "a3", "label": "\\N", "shape": "ellipse", "color": "white", "style": "filled"},
         |    {"_gvid": 6, "name": "b0", "label": "\\N", "shape": "ellipse", "style": "filled"},
         |    {"_gvid": 7, "name": "b1", "label": "\\N", "shape": "ellipse", "style": "filled"},
         |    {"_gvid": 8, "name": "b2", "label": "\\N", "shape": "ellipse", "style": "filled"},
         |    {"_gvid": 9, "name": "b3", "label": "\\N", "shape": "ellipse", "style": "filled"},
         |    {"_gvid": 10, "name": "start", "label": "\\N", "shape": "Mdiamond"},
         |    {"_gvid": 11, "name": "end", "label": "\\N", "shape": "Msquare"}
         |  ],
         |  "edges": [
         |    {"_gvid": 9, "tail": 8, "head": 9},
         |    {"_gvid": 10, "tail": 9, "head": 11},
         |    {"_gvid": 7, "tail": 7, "head": 8},
         |    {"_gvid": 0, "tail": 2, "head": 3},
         |    {"_gvid": 4, "tail": 5, "head": 2},
         |    {"_gvid": 12, "tail": 10, "head": 6},
         |    {"_gvid": 2, "tail": 3, "head": 9},
         |    {"_gvid": 11, "tail": 10, "head": 2},
         |    {"_gvid": 3, "tail": 4, "head": 5},
         |    {"_gvid": 1, "tail": 3, "head": 4},
         |    {"_gvid": 6, "tail": 6, "head": 7},
         |    {"_gvid": 5, "tail": 5, "head": 11},
         |    {"_gvid": 8, "tail": 8, "head": 5}
         |  ],
         |  "label": ""
         |}""".stripMargin

    val simpleGraph  = read[SimpleGraph](json)
    val visibleGraph = simplegraph.toViewerGraph(simpleGraph).toVisibleGraph(ElementIds())
    val dotString    = viewerGraphElementsToText(visibleGraph.elements)

    // Verify that cluster_0 appears before cluster_1 in the DOT output
    val cluster0Pos = dotString.indexOf("subgraph \"cluster_0\"")
    val cluster1Pos = dotString.indexOf("subgraph \"cluster_1\"")

    assert(cluster0Pos > 0, "cluster_0 should be present in the DOT output")
    assert(cluster1Pos > 0, "cluster_1 should be present in the DOT output")
    assert(cluster0Pos < cluster1Pos, "cluster_0 should appear before cluster_1")

    // Extract content of cluster_0
    val cluster0Start = dotString.indexOf("{", cluster0Pos)
    val cluster0End = {
      var braceCount = 1
      var pos        = cluster0Start + 1
      while (braceCount > 0 && pos < dotString.length) {
        if (dotString(pos) == '{') braceCount += 1
        else if (dotString(pos) == '}') braceCount -= 1
        pos += 1
      }
      pos
    }
    val cluster0Content = dotString.substring(cluster0Start, cluster0End)

    // Verify that nodes within cluster_0 appear in the order: a0, a1, a2, a3
    val a0Pos = cluster0Content.indexOf("\"a0\"")
    val a1Pos = cluster0Content.indexOf("\"a1\"")
    val a2Pos = cluster0Content.indexOf("\"a2\"")
    val a3Pos = cluster0Content.indexOf("\"a3\"")

    assert(a0Pos > 0, "a0 should be in cluster_0")
    assert(a1Pos > 0, "a1 should be in cluster_0")
    assert(a2Pos > 0, "a2 should be in cluster_0")
    assert(a3Pos > 0, "a3 should be in cluster_0")

    assert(a0Pos < a1Pos, "a0 should appear before a1 in cluster_0")
    assert(a1Pos < a2Pos, "a1 should appear before a2 in cluster_0")
    assert(a2Pos < a3Pos, "a2 should appear before a3 in cluster_0")

    // Extract content of cluster_1
    val cluster1Start = dotString.indexOf("{", cluster1Pos)
    val cluster1End = {
      var braceCount = 1
      var pos        = cluster1Start + 1
      while (braceCount > 0 && pos < dotString.length) {
        if (dotString(pos) == '{') braceCount += 1
        else if (dotString(pos) == '}') braceCount -= 1
        pos += 1
      }
      pos
    }
    val cluster1Content = dotString.substring(cluster1Start, cluster1End)

    // Verify that nodes within cluster_1 appear in the order: b0, b1, b2, b3
    val b0Pos = cluster1Content.indexOf("\"b0\"")
    val b1Pos = cluster1Content.indexOf("\"b1\"")
    val b2Pos = cluster1Content.indexOf("\"b2\"")
    val b3Pos = cluster1Content.indexOf("\"b3\"")

    assert(b0Pos > 0, "b0 should be in cluster_1")
    assert(b1Pos > 0, "b1 should be in cluster_1")
    assert(b2Pos > 0, "b2 should be in cluster_1")
    assert(b3Pos > 0, "b3 should be in cluster_1")

    assert(b0Pos < b1Pos, "b0 should appear before b1 in cluster_1")
    assert(b1Pos < b2Pos, "b1 should appear before b2 in cluster_1")
    assert(b2Pos < b3Pos, "b2 should appear before b3 in cluster_1")
  }

  test("default attributes should be preserved") {
    // digraph "G" {
    //  "b" [label="b"];
    //  "a" [label="a"];
    //  "a" -> "b" [label="f"];
    // }
    val json =
      """|{
         |  "name": "G",
         |  "directed": true,
         |  "objects": [
         |    {"_gvid": 0, "name": "b", "label": "b"},
         |    {"_gvid": 1, "name": "a", "label": "a"}
         |  ],
         |  "edges": [
         |    {"_gvid": 0, "tail": 1, "head": 0, "label": "f"}
         |  ]
         |}""".stripMargin

    val simpleGraph  = read[SimpleGraph](json)
    val visibleGraph = simplegraph.toViewerGraph(simpleGraph).toVisibleGraph(ElementIds())

    val dotString = viewerGraphElementsToText(visibleGraph.elements, omitInternal = false)

    val similarToExpected =
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
         |  "b" [
         |    id="node:b",
         |    label="b"
         |  ];
         |  "a" [
         |    id="node:a",
         |    label="a"
         |  ];
         |  "a" -> "b" [
         |    id="arrow:a->b/0",
         |    label="f"
         |  ];
         |}""".stripMargin

    assertNoDiff(dotString, similarToExpected, "DOT output should match expected format with default attributes preserved")

  }

  test("default attribute should be combined") {
    val elementsJson =
      """|{
         |  "nodes": {
         |     "n1": {
         |      "$type": "ViewerNode",
         |      "id": "n1",
         |      "attributes": {
         |        "_gvid": "0",
         |        "label": "n1: Inherits Default",
         |        "fillstyle": "true",
         |        "cornerstyle": "rounded"
         |      }
         |    },
         |    "n2": {
         |      "$type": "ViewerNode",
         |      "id": "n2",
         |      "attributes": {
         |        "_gvid": "1",
         |        "label": "n2: Resets to Primitive"
         |      }
         |    },
         |    "n3": {
         |      "$type": "ViewerNode",
         |      "id": "n3",
         |      "attributes": {
         |        "_gvid": "2",
         |        "label": "n3: Explicitly 'solid'",
         |        "borderstyle": "solid"
         |      }
         |    }
         |  },
         |  "arrows": {
         |    "n1->n2/0": {
         |      "$type": "Arrow",
         |      "source": "n1",
         |      "target": "n2",
         |      "attributes": {
         |        "_gvid": "0"
         |      },
         |      "seq": 0
         |    },
         |    "n2->n3/1": {
         |      "$type": "Arrow",
         |      "source": "n2",
         |      "target": "n3",
         |      "attributes": {
         |        "_gvid": "1"
         |      }
         |    }
         |  },
         |  "graphAttributes": {
         |    "directed": "true"
         |  },
         |  "defaultNodeAttributes": {
         |    "sides": "5",
         |    "shape": "box",
         |    "fillcolor": "lightblue",
         |    "fillstyle": "true"
         |  },
         |  "defaultArrowAttributes": {
         |    "dir": "both",
         |    "arrowhead": "vee",
         |    "arrowtail": "none"
         |  }
         |}""".stripMargin

    val elements = read[ViewerGraphElements](elementsJson)

    val expected =
      """|digraph "G" {
         |  node [
         |    sides="5",
         |    shape="box",
         |    fillcolor="lightblue",
         |    style="filled"
         |  ];
         |  edge [
         |    dir="both",
         |    arrowhead="vee",
         |    arrowtail="none"
         |  ];
         |  "n1" [
         |    id="node:n1",
         |    label="n1: Inherits Default",
         |    style="filled,rounded"
         |  ];
         |  "n2" [
         |    id="node:n2",
         |    label="n2: Resets to Primitive"
         |  ];
         |  "n3" [
         |    id="node:n3",
         |    label="n3: Explicitly 'solid'",
         |    style="filled"
         |  ];
         |  "n1" -> "n2" [id="arrow:n1->n2/0"];
         |  "n2" -> "n3" [id="arrow:n2->n3/1"];
         |}""".stripMargin

    val dotString = viewerGraphElementsToText(elements.combineStyleAttributes, omitInternal = false)

    assertNoDiff(dotString, expected)
  }

  test("standard example should be rendered reasonably") {
    val dot_source =
      """|digraph "G" {
         |   node [ shape="ellipse" ];
         |   subgraph cluster_0 {
         |       graph [ style="filled", label="process #1", color="lightgrey", ];
         |       "a0" [ style="filled", color="white" ];
         |       "a1" [ style="filled", color="white" ];
         |   }
         |   subgraph cluster_1 {
         |       graph [ label="process #2", color="blue", ];
         |       "b0" [style="filled"];
         |       "b1" [style="filled"];
         |   }
         |   "start" [shape="Mdiamond"];
         |   "a0" -> "a1";
         |   "start" -> "b0";
         |   "start" -> "a0";
         |   "b0" -> "b1";
         |}""".stripMargin

    val simple_graph_json =
      """|{
         |  "name": "G",
         |  "directed": true,
         |  "strict": false,
         |  "label": "",
         |  "objects": [
         |    {"name": "cluster_0", "color": "lightgrey", "label": "process #1", "style": "filled", "_gvid": 0, "nodes": [2, 3], "edges": [0]},
         |    {"name": "cluster_1", "color": "blue", "label": "process #2", "_gvid": 1, "nodes": [4, 5], "edges": [1]},
         |    {"_gvid": 2, "name": "a0", "color": "white", "label": "\\N", "shape": "ellipse", "style": "filled"},
         |    {"_gvid": 3, "name": "a1", "color": "white", "label": "\\N", "shape": "ellipse", "style": "filled"},
         |    {"_gvid": 4, "name": "b0", "label": "\\N", "shape": "ellipse", "style": "filled"},
         |    {"_gvid": 5, "name": "b1", "label": "\\N", "shape": "ellipse", "style": "filled"},
         |    {"_gvid": 6, "name": "start", "label": "\\N", "shape": "Mdiamond"}
         |  ],
         |  "edges": [
         |    {"_gvid": 0, "tail": 2, "head": 3},
         |    {"_gvid": 3, "tail": 6, "head": 4},
         |    {"_gvid": 2, "tail": 6, "head": 2},
         |    {"_gvid": 1, "tail": 4, "head": 5}
         |  ]
         |}
         |
         |""".stripMargin

    val simpleGraph = read[SimpleGraph](simple_graph_json)
    val viewerGraph = simplegraph.toViewerGraph(simpleGraph).toVisibleGraph()
    val text        = viewerGraphToText(viewerGraph, omitInternal = true)

    println(text)
  }

  test("nested group with styles") {
    val dot_source =
      """|digraph "G" {
         |    graph [label=""];
         |    subgraph "G1" {
         |        graph [style="filled,rounded", label="group 1", cluster="true", fillcolor="#fff085"]; subgraph "G2" {
         |            graph [style="filled", label="group 2", cluster="true", fillcolor="#7bf1a8"];
         |            "c" [label="c", shape="circle", fillcolor="#615fff", style="filled,dotted"];
         |        }
         |        subgraph "G3" {
         |            graph [style="filled,dashed", label="group 3", cluster="true", fillcolor="#ffa2a2"];
         |            "b" [label="b", shape="circle", fillcolor="#b8e6fe", style="filled"];
         |        }
         |    }
         |    "a" [label="a", shape="circle", fillcolor="#b9f8cf", style="filled"];
         |    "a" -> "b" [style="dashed"];
         |    "b" -> "c" [style="dashed"];
         |}""".stripMargin

    val simple_graph_json =
      """|{
         |  "name": "G",
         |  "directed": true,
         |  "strict": false,
         |  "bb": "0 0 91 254",
         |  "label": "",
         |  "_subgraph_cnt": 3,
         |  "objects": [
         |    {"name": "G1", "cluster": "true", "fillcolor": "#fff085", "label": "group 1", "style": "filled,rounded", "_gvid": 0, "subgraphs": [1, 2], "nodes": [3,4], "edges": [0]},
         |    {"name": "G2", "cluster": "true", "fillcolor": "#7bf1a8", "label": "group 2", "style": "filled", "_gvid": 1, "nodes": [3]},
         |    {"name": "G3", "cluster": "true", "fillcolor": "#ffa2a2", "label": "group 3", "style": "filled,dashed", "_gvid": 2, "nodes": [4]},
         |    {"_gvid": 3, "name": "c", "fillcolor": "#615fff", "label": "c", "shape": "circle", "style": "filled,dotted"},
         |    {"_gvid": 4, "name": "b", "fillcolor": "#b8e6fe", "label": "b", "shape": "circle", "style": "filled"},
         |    {"_gvid": 5, "name": "a", "fillcolor": "#b9f8cf", "label": "a", "shape": "circle", "style": "filled"}
         |  ],
         |  "edges": [
         |    {"_gvid": 1, "tail": 5, "head": 4, "style": "dashed"},
         |    {"_gvid": 0, "tail": 4, "head": 3, "style": "dashed"}
         |  ]
         |}
         |
         |""".stripMargin

    val simpleGraph = read[SimpleGraph](simple_graph_json)
    val viewerGraph = simplegraph.toViewerGraph(simpleGraph).toVisibleGraph()
    val text        = viewerGraphToText(viewerGraph, omitInternal = true)

    println(text)
  }
