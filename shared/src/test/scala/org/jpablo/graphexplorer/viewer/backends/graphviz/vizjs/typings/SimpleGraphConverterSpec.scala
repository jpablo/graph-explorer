package org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.typings

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.typings.{SimpleGraph, SimpleGraphConverter}
import org.jpablo.graphexplorer.viewer.graph.ViewerGraphElements
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import upickle.default.*

class SimpleGraphConverterSpec extends FunSuite:

  val sampleJson = """{
  "name": "G",
  "directed": true,
  "strict": false,
  "bb": "0,0,144,36",
  "bgcolor": "#ffc9c9",
  "rankdir": "LR",
  "splines": "line",
  "_subgraph_cnt": 0,
  "objects": [
    {
      "_gvid": 0,
      "name": "b",
      "fillcolor": "#b9f8cf",
      "height": "0.5",
      "id": "node:b",
      "label": "b",
      "pos": "117,18",
      "shape": "box",
      "sides": "5",
      "style": "filled",
      "width": "0.75"
    },
    {
      "_gvid": 1,
      "name": "a",
      "height": "0.5",
      "id": "node:a",
      "label": "a",
      "pos": "27,18",
      "shape": "ellipse",
      "sides": "5",
      "width": "0.75"
    }
  ],
  "edges": [
    {
      "_gvid": 0,
      "tail": 1,
      "head": 0,
      "arrowhead": "vee",
      "arrowtail": "box",
      "dir": "both",
      "id": "arrow:a->b/1",
      "pos": "s,48.41,6.3984 e,89.687,5.5202 58.505,5.081 65.01,4.504 71.952,4.3568 78.68,4.6392"
    },
    {
      "_gvid": 1,
      "tail": 1,
      "head": 0,
      "arrowhead": "none",
      "arrowtail": "none",
      "dir": "both",
      "id": "arrow:a->b/2",
      "pos": "54.403,18 65.541,18 78.48,18 89.616,18"
    },
    {
      "_gvid": 2,
      "tail": 1,
      "head": 0,
      "arrowhead": "none",
      "arrowtail": "odot",
      "dir": "both",
      "id": "arrow:a->b/3",
      "pos": "s,48.41,29.602 57.228,30.8 67.713,31.83 79.464,31.723 89.687,30.48"
    }
  ]
}"""

  test("SimpleGraphConverter should convert sample graph to ViewerGraphElements") {
    val graph    = read[SimpleGraph](sampleJson)
    val elements = SimpleGraphConverter.toViewerGraphElements(graph)

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
    val elements                = SimpleGraphConverter.toViewerGraphElements(emptyGraph)

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
    val elements           = SimpleGraphConverter.toViewerGraphElements(graph)

    // Verify nodes from both main graph and subgraph
    assertEquals(elements.nodes.size, 3)
    assert(elements.nodes.contains(NodeId("mainNode")))
    assert(elements.nodes.contains(NodeId("subNode1")))
    assert(elements.nodes.contains(NodeId("subNode2")))

    // Verify subgraph was converted to a group
    assertEquals(elements.groups.size, 1)
    assert(elements.groups.contains(GroupId("cluster_0")))

    // Verify membership relationships
    assertEquals(elements.memberships.size, 2)
    assertEquals(elements.memberships.get(NodeId("subNode1")), Some(GroupId("cluster_0")))
    assertEquals(elements.memberships.get(NodeId("subNode2")), Some(GroupId("cluster_0")))

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
    val elements           = SimpleGraphConverter.toViewerGraphElements(graph)

    assertEquals(elements.nodes.size, 2)
    assertEquals(elements.arrows.size, 1)

    val arrow = elements.arrows.values.head
    assertEquals(arrow.source, NodeId("first"))
    assertEquals(arrow.target, NodeId("second"))
  }

  test("SimpleGraphConverter should preserve edge IDs in DOT output") {
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
      arrows = Map(
        arrow1.id -> arrow1,
        arrow2.id -> arrow2
      ),
      memberships = VectorMap.empty,
      groups = Map.empty,
      graphAttributes = Attributes.empty,
      defaultNodeAttributes = Attributes.empty,
      defaultArrowAttributes = Attributes.empty,
      defaultGroupAttributes = Attributes.empty
    )

    // Convert to DOT string
    val dotString = SimpleGraphConverter.viewerGraphElementsToDotString(elements)

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
            AttributeId("label")  -> AttrValue("a"),
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
            AttributeId("label")   -> AttrValue("A title"),
            AttributeId("lheight") -> AttrValue("0.23"),
            AttributeId("lp")      -> AttrValue("43,97.2"),
            AttributeId("lwidth")  -> AttrValue("0.49"),
            AttributeId("cluster") -> AttrValue("true")
          ))
        )
      ),
      graphAttributes = Attributes(VectorMap(
        AttributeId("label")   -> AttrValue("A title"),
        AttributeId("rankdir") -> AttrValue("LR")
      )),
      defaultNodeAttributes = Attributes.empty,
      defaultArrowAttributes = Attributes.empty,
      defaultGroupAttributes = Attributes.empty
    )

    // Convert to DOT string
    val dotString = SimpleGraphConverter.viewerGraphElementsToDotString(elements)

    val expected =
      """|digraph "G" {
         |  graph [
         |    label="A title",
         |    rankdir="LR"
         |  ];
         |  subgraph "first_group" {
         |    graph [
         |      id="group:first_group",
         |      label="A title",
         |      lheight="0.23",
         |      lp="43,97.2",
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

    val graph = read[SimpleGraph](graphJson)
    val elements = SimpleGraphConverter.toViewerGraphElements(graph)
    
    // Verify the main graph has rankdir
    assertEquals(elements.graphAttributes.values(AttributeId("rankdir")), AttrValue("LR"))
    
    // Verify the subgraph does NOT have rankdir after conversion
    val groupId = GroupId("gdd30b2d0")
    val subgroupAttrs = elements.groups(groupId).attributes
    assert(!subgroupAttrs.values.contains(AttributeId("rankdir")), 
      "Subgraph should not have rankdir attribute after conversion from SimpleGraph")
    
    // Verify other subgraph attributes are preserved
    assertEquals(subgroupAttrs.values(AttributeId("label")), AttrValue("A title"))
    assertEquals(subgroupAttrs.values(AttributeId("style")), AttrValue("filled"))
    assertEquals(subgroupAttrs.values(AttributeId("cluster")), AttrValue("true"))
  }
