package org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.SimpleGraph
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Rank, RankType}
import org.jpablo.graphexplorer.viewer.models.*
import upickle.default.*

class ToViewerGraphElementsSpec extends FunSuite:

  test("SimpleGraphConverter should convert sample graph to ViewerGraphElements") {
    val sampleJson =
      """{
        |  "name": "G",
        |  "directed": true,
        |  "strict": false,
        |  "bb": "0,0,144,36",
        |  "bgcolor": "#ffc9c9",
        |  "rankdir": "LR",
        |  "splines": "line",
        |  "_subgraph_cnt": 0,
        |  "objects": [
        |    {"_gvid": 0, "name": "b", "fillcolor": "#b9f8cf", "height": "0.5", "id": "node:b", "label": "b", "pos": "117,18", "shape": "box", "sides": "5", "style": "filled", "width": "0.75"},
        |    {"_gvid": 1, "name": "a", "height": "0.5", "id": "node:a", "label": "a", "pos": "27,18", "shape": "ellipse", "sides": "5", "width": "0.75"}
        |  ],
        |  "edges": [
        |    {"_gvid": 0, "tail": 1, "head": 0, "arrowhead": "vee", "arrowtail": "box", "dir": "both", "id": "arrow:a->b/1", "pos": "s,48.41,6.3984 e,89.687,5.5202 58.505,5.081 65.01,4.504 71.952,4.3568 78.68,4.6392"},
        |    {"_gvid": 1, "tail": 1, "head": 0, "arrowhead": "none", "arrowtail": "none", "dir": "both", "id": "arrow:a->b/2", "pos": "54.403,18 65.541,18 78.48,18 89.616,18"},
        |    {"_gvid": 2, "tail": 1, "head": 0, "arrowhead": "none", "arrowtail": "odot", "dir": "both", "id": "arrow:a->b/3", "pos": "s,48.41,29.602 57.228,30.8 67.713,31.83 79.464,31.723 89.687,30.48"}
        |  ]
        |}""".stripMargin

    val graph    = read[SimpleGraph](sampleJson)
    val elements = simplegraph.toViewerGraphElements(graph)

    // Verify nodes are created correctly
    assertEquals(elements.nodes.size, 2)
    assert(elements.nodes.contains(NodeId("a")))
    assert(elements.nodes.contains(NodeId("b")))

    // Verify node attributes are preserved
    val nodeA = elements.nodes(NodeId("a"))
    val nodeB = elements.nodes(NodeId("b"))

    // Check that node attributes contain the expected values
    assertEquals(nodeA.attributes.get(AttributeId("shape")).map(_.value), Some("ellipse"))
    assertEquals(nodeA.attributes.get(AttributeId("label")).map(_.value), Some("a"))
    assertEquals(nodeB.attributes.get(AttributeId("shape")).map(_.value), Some("box"))
    assertEquals(nodeB.attributes.get(AttributeId("fillcolor")).map(_.value), Some("#b9f8cf"))
    assertEquals(nodeB.attributes.get(AttributeId("style")).map(_.value), Some("filled"))

    // Verify arrows are created correctly
    assertEquals(elements.arrows.size, 3)

    // Check that arrows have proper IDs and sequence numbers
    val arrowIds = elements.arrows.keys.map(_.value).toSet
    assert(arrowIds.contains("a->b/0"))
    assert(arrowIds.contains("a->b/1"))
    assert(arrowIds.contains("a->b/2"))

    // Verify all arrows point from a to b with correct sequence numbers
    val arrow1 = elements.arrows(ArrowId("a->b/0"))
    val arrow2 = elements.arrows(ArrowId("a->b/1"))
    val arrow3 = elements.arrows(ArrowId("a->b/2"))

    assertEquals(arrow1.source, NodeId("a"))
    assertEquals(arrow1.target, NodeId("b"))
    assertEquals(arrow1.seq, 0)

    assertEquals(arrow2.source, NodeId("a"))
    assertEquals(arrow2.target, NodeId("b"))
    assertEquals(arrow2.seq, 1)

    assertEquals(arrow3.source, NodeId("a"))
    assertEquals(arrow3.target, NodeId("b"))
    assertEquals(arrow3.seq, 2)

    // Verify arrow attributes are preserved
    assertEquals(arrow1.attributes.get(AttributeId("arrowhead")).map(_.value), Some("vee"))
    assertEquals(arrow1.attributes.get(AttributeId("arrowtail")).map(_.value), Some("box"))
    assertEquals(arrow1.attributes.get(AttributeId("dir")).map(_.value), Some("both"))

    assertEquals(arrow2.attributes.get(AttributeId("arrowhead")).map(_.value), Some("none"))
    assertEquals(arrow3.attributes.get(AttributeId("arrowtail")).map(_.value), Some("odot"))

    // Verify no groups or memberships for this simple graph
    assertEquals(elements.groups.size, 0)
    assertEquals(elements.memberships.size, 0)

    // Verify graph attributes are merged properly
    assertEquals(elements.graphAttributes.get(AttributeId("bgcolor")).map(_.value), Some("#ffc9c9"))
    assertEquals(elements.graphAttributes.get(AttributeId("rankdir")).map(_.value), Some("LR"))
    assertEquals(elements.graphAttributes.get(AttributeId("splines")).map(_.value), Some("line"))
  }

  test("SimpleGraphConverter should handle empty graph") {
    val emptyGraph: SimpleGraph = read[SimpleGraph]("""{"name": "empty"}""")
    val elements                = simplegraph.toViewerGraphElements(emptyGraph)

    assertEquals(elements.nodes.size, 0)
    assertEquals(elements.arrows.size, 0)
    assertEquals(elements.groups.size, 0)
    assertEquals(elements.memberships.size, 0)
  }

  test("SimpleGraphConverter should handle graph with subgraphs") {
    val subgraphJson = """{
      "name": "MainGraph",
      "directed": true,
      "strict": false,
      "bb": "0,0,300,200",
      "_subgraph_cnt": 1,
      "objects": [
        {
          "_gvid": 1.0,
          "name": "cluster_0",
          "bb": "10,10,150,100",
          "nodes": [0.0, 1.0],
          "label": "cluster_0"
        },
        {
          "_gvid": 0,
          "name": "subNode1",
          "label": "subNode1"
        },
        {
          "_gvid": 1,
          "name": "subNode2",
          "label": "subNode2"
        },
        {
          "_gvid": 2,
          "name": "mainNode",
          "label": "mainNode"
        }
      ],
      "edges": [
        {
          "_gvid": 0,
          "tail": 0,
          "head": 1,
          "id": "subNode1->subNode2/1"
        }
      ]
    }"""

    val graph: SimpleGraph = read[SimpleGraph](subgraphJson)
    val elements           = simplegraph.toViewerGraphElements(graph)

    // Verify nodes from both main graph and subgraph
    assertEquals(elements.nodes.size, 3)
    assert(elements.nodes.contains(NodeId("mainNode")))
    assert(elements.nodes.contains(NodeId("subNode1")))
    assert(elements.nodes.contains(NodeId("subNode2")))

    // Verify subgraph was converted to a group
    assertEquals(elements.groups.size, 1)
    assert(elements.groups.contains(GroupId("0")))

    // Verify membership relationships
    assertEquals(elements.memberships.size, 2)
    assertEquals(elements.memberships.get(NodeId("subNode1")), Some(GroupId("0")))
    assertEquals(elements.memberships.get(NodeId("subNode2")), Some(GroupId("0")))

    // Verify arrow within subgraph
    assertEquals(elements.arrows.size, 1)
    val arrow = elements.arrows.values.head
    assertEquals(arrow.source, NodeId("subNode1"))
    assertEquals(arrow.target, NodeId("subNode2"))
  }

  test("SimpleGraphConverter should handle numeric node references in edges") {
    val numericRefJson = """{
      "name": "test",
      "directed": true,
      "strict": false,
      "bb": "0,0,100,100",
      "_subgraph_cnt": 0,
      "objects": [
        {"_gvid": 0, "name": "first", "label": "first"},
        {"_gvid": 1, "name": "second", "label": "second"}
      ],
      "edges": [
        {
          "_gvid": 0,
          "tail": 0,
          "head": 1,
          "id": "first->second/1"
        }
      ]
    }"""

    val graph: SimpleGraph = read[SimpleGraph](numericRefJson)
    val elements           = simplegraph.toViewerGraphElements(graph)

    assertEquals(elements.nodes.size, 2)
    assertEquals(elements.arrows.size, 1)

    val arrow = elements.arrows.values.head
    assertEquals(arrow.source, NodeId("first"))
    assertEquals(arrow.target, NodeId("second"))
  }

  test("round-trip conversion should not propagate rankdir to subgraphs") {
    // Simulate the bug scenario: viz.js returns a SimpleGraph with rankdir on a cluster
    val graphJson = """{
      "name": "G",
      "directed": true,
      "label": "Diagram",
      "rankdir": "LR",
      "objects": [
        {
          "_gvid": 1.0,
          "name": "gdd30b2d0",
          "label": "A title",
          "style": "filled",
          "cluster": "true",
          "rankdir": "LR",
          "nodes": [0]
        },
        {
          "_gvid": 0,
          "name": "a",
          "label": "a",
          "fillcolor": "#bedbff",
          "style": "filled"
        }
      ]
    }"""

    val graph    = read[SimpleGraph](graphJson)
    val elements = simplegraph.toViewerGraphElements(graph)

    // Verify the main graph has rankdir
    assertEquals(elements.graphAttributes.values(AttributeId("rankdir")), AttrValue("LR"))

    // Verify the subgraph does NOT have rankdir after conversion
    val groupId       = GroupId("gdd30b2d0")
    val subgroupAttrs = elements.groups(groupId).attributes
    assert(
      !subgroupAttrs.values.contains(AttributeId("rankdir")),
      "Subgraph should not have rankdir attribute after conversion from SimpleGraph"
    )

    // Verify other subgraph attributes are preserved
    assertEquals(subgroupAttrs.values(AttributeId("label")), AttrValue("A title"))
    assertEquals(subgroupAttrs.values(AttributeId("style")), AttrValue("filled"))
    assertEquals(subgroupAttrs.values(AttributeId("cluster")), AttrValue("true"))
  }

  test("rank attribute on subgraphs should be preserved") {
    val json =
      """{
        |  "name": "R",
        |  "directed": true,
        |  "strict": false,
        |  "bb": "0,0,198,256",
        |  "_subgraph_cnt": 2,
        |  "objects": [
        |    {"name": "%1", "rank": "same", "_gvid": 0, "nodes": [2, 3, 4]},
        |    {"name": "%3", "rank": "same", "_gvid": 1, "nodes": [5, 6, 7]},
        |    {"_gvid": 2, "name": "rA", "height": "0.51389", "label": "\\N", "pos": "27,164.5", "rects": "0,146.5,53.772,182.5", "shape": "record", "width": "0.75"},
        |    {"_gvid": 3, "name": "sA", "height": "0.51389", "label": "\\N", "pos": "99,164.5", "rects": "72,146.5,125.56,182.5", "shape": "record", "width": "0.75"},
        |    {"_gvid": 4, "name": "tA", "height": "0.51389", "label": "\\N", "pos": "171,164.5", "rects": "144,146.5,198,182.5", "shape": "record", "width": "0.75"},
        |    {"_gvid": 5, "name": "uB", "height": "0.51389", "label": "\\N", "pos": "27,91.5", "rects": "0,73.5,53.338,109.5", "shape": "record", "width": "0.75"},
        |    {"_gvid": 6, "name": "vB", "height": "0.51389", "label": "\\N", "pos": "99,91.5", "rects": "72,73.5,125.34,109.5", "shape": "record", "width": "0.75"},
        |    {"_gvid": 7, "name": "wB", "height": "0.51389", "label": "\\N", "pos": "171,91.5", "rects": "144,73.5,197.45,109.5", "shape": "record", "width": "0.75"},
        |    {"_gvid": 8, "name": "t", "height": "0.51389", "label": "\\N", "pos": "27,237.5", "rects": "0,219.5,53.89,255.5", "shape": "record", "width": "0.75"},
        |    {"_gvid": 9, "name": "u", "height": "0.51389", "label": "\\N", "pos": "171,18.5", "rects": "144,0.5,198,36.5", "shape": "record", "width": "0.75"}
        |  ],
        |  "edges": [
        |    {"_gvid": 0, "tail": 2, "head": 3},
        |    {"_gvid": 1, "tail": 3, "head": 6, "pos": "e,99,109.53 99,146.31 99,138.73 99,129.6 99,121.04"},
        |    {"_gvid": 5, "tail": 8, "head": 2, "pos": "e,27,182.53 27,219.31 27,211.73 27,202.6 27,194.04"},
        |    {"_gvid": 2, "tail": 5, "head": 6},
        |    {"_gvid": 4, "tail": 7, "head": 9, "pos": "e,171,36.529 171,73.313 171,65.726 171,56.601 171,48.039"},
        |    {"_gvid": 3, "tail": 7, "head": 4, "pos": "e,171,146.31 171,109.53 171,117.18 171,126.42 171,135.08"}
        |  ]
        |}""".stripMargin

    val simpleGraph    = read[SimpleGraph](json)
    val viewerElements = simplegraph.toViewerGraphElements(simpleGraph)

    for ((_, group) <- viewerElements.groups) do
      assertEquals(group.attributes.get(Rank), Some(AttrValue(RankType.same.toString)))

  }

  test("SimpleGraphConverter should preserve node order from SimpleGraph") {
    // Test with the existing sample JSON that has nodes in specific order: "b" first, "a" second
    val sampleJson =
      """{
        |  "name": "G",
        |  "directed": true,
        |  "strict": false,
        |  "bb": "0,0,144,36",
        |  "bgcolor": "#ffc9c9",
        |  "rankdir": "LR",
        |  "splines": "line",
        |  "_subgraph_cnt": 0,
        |  "objects": [
        |    {"_gvid": 0, "name": "b", "fillcolor": "#b9f8cf", "height": "0.5", "id": "node:b", "label": "b", "pos": "117,18", "shape": "box", "sides": "5", "style": "filled", "width": "0.75"},
        |    {"_gvid": 1, "name": "a", "height": "0.5", "id": "node:a", "label": "a", "pos": "27,18", "shape": "ellipse", "sides": "5", "width": "0.75"}
        |  ],
        |  "edges": [
        |    {"_gvid": 0, "tail": 1, "head": 0, "arrowhead": "vee", "arrowtail": "box", "dir": "both", "id": "arrow:a->b/1", "pos": "s,48.41,6.3984 e,89.687,5.5202 58.505,5.081 65.01,4.504 71.952,4.3568 78.68,4.6392"}
        |  ]
        |}""".stripMargin

    val graph    = read[SimpleGraph](sampleJson)
    val elements = simplegraph.toViewerGraphElements(graph)

    // Verify that VectorMap preserves the order: "b" should come before "a"
    val nodeOrder = elements.nodes.keys.toList
    assertEquals(nodeOrder, List(NodeId("b"), NodeId("a")))

    // Also verify the nodes exist with correct attributes
    assertEquals(elements.nodes.size, 2)
    assert(elements.nodes.contains(NodeId("a")))
    assert(elements.nodes.contains(NodeId("b")))
  }
