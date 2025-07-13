package org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.typings

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.typings.*
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.typings.SimpleGraphObject.{Cluster, Node}
import org.jpablo.graphexplorer.viewer.graph.ViewerGraphElements
import upickle.default.*
import scala.collection.immutable.VectorMap

class GraphToDotSpec extends FunSuite:

  test("graphToDotString should handle empty graph") {
    val graph  = SimpleGraph(name = "G")
    val result = SimpleGraphConverter.graphToDotString(graph)

    assertEquals(
      result,
      """digraph "G" {
}"""
    )
  }

  test("graphToDotString should handle simple graph with nodes and edges") {
    val nodeA = SimpleGraphNode(_gvid = 0, name = "a", label = "Node A")
    val nodeB = SimpleGraphNode(_gvid = 1, name = "b", label = "Node B")
    val edge = SimpleGraphEdge(
      _gvid = 0,
      tail = 0,
      head = 1,
      label = Some("connection")
    )

    val graph = SimpleGraph(
      name = "SimpleGraph",
      objects = Some(List(Node(nodeA), Node(nodeB))),
      edges = Some(List(edge))
    )

    val result   = SimpleGraphConverter.graphToDotString(graph)
    val expected = """digraph "SimpleGraph" {
  "a" [label="Node A"];
  "b" [label="Node B"];
  "a" -> "b" [label="connection"];
}"""

    assertEquals(result, expected)
  }

  test("graphToDotString should handle graph attributes") {
    val graph = SimpleGraph(
      name = "AttributedGraph",
      rankdir = Some("LR"),
      bgcolor = Some("white"),
      splines = Some("ortho")
    )

    val result   = SimpleGraphConverter.graphToDotString(graph)
    val expected = """digraph "AttributedGraph" {
  graph [rankdir="LR", bgcolor="white", splines="ortho"];
}"""

    assertEquals(result, expected)
  }

  test("graphToDotString should handle nodes with attributes") {
    val node = SimpleGraphNode(
      _gvid = 0,
      name = "test",
      label = "Test Node",
      shape = Some("ellipse"),
      fontsize = Some("12")
    )

    val graph = SimpleGraph(
      name = "G",
      objects = Some(List(Node(node)))
    )

    val result   = SimpleGraphConverter.graphToDotString(graph)
    val expected = """digraph "G" {
  "test" [label="Test Node", shape="ellipse", fontsize="12"];
}"""

    assertEquals(result, expected)
  }

  test("graphToDotString should handle boolean and numeric attributes") {
    val node = SimpleGraphNode(
      _gvid = 0,
      name = "test",
      height = Some("0.8"),
      width = Some("1.5"),
      label = "test",
      fixedsize = Some("true")
    )

    val graph = SimpleGraph(
      name = "G",
      objects = Some(List(Node(node)))
    )

    val result = SimpleGraphConverter.graphToDotString(graph)
    val expected =
      """|digraph "G" {
         |  "test" [label="test", height="0.8", width="1.5", fixedsize="true"];
         |}""".stripMargin

    assertEquals(result, expected)
  }

  test("graphToDotString should handle HTML-like labels") {
    // HTML labels should be formatted with <> notation instead of quotes
    val node = SimpleGraphNode(
      _gvid = 0,
      name = "html",
      label = "<b>Bold Label</b>"
    )

    val graph = SimpleGraph(
      name = "G",
      objects = Some(List(Node(node)))
    )

    val result   = SimpleGraphConverter.graphToDotString(graph)
    val expected = """digraph "G" {
  "html" [label=<<b>Bold Label</b>>];
}"""

    assertEquals(result, expected)
  }

  test("graphToDotString should handle subgraphs") {
    val nodeA = SimpleGraphNode(
      _gvid = 0,
      name = "a",
      label = "A"
    )
    val nodeB = SimpleGraphNode(
      _gvid = 1,
      name = "b",
      label = "B"
    )
    val nodeC = SimpleGraphNode(
      _gvid = 2,
      name = "c",
      label = "C"
    )

    val cluster = SimpleGraphCluster(
      _gvid = 100.0,
      name = "1",
//      bb = "0,0,100,100",
      nodes = List(0, 1),
      label = "Group 1"
    )

    val edgeAB = SimpleGraphEdge(
      _gvid = 0,
      tail = 0,
      head = 1,
      label = Some("internal")
    )
    val edgeBC = SimpleGraphEdge(
      _gvid = 1,
      tail = 1,
      head = 2,
      label = Some("external")
    )

    val graph = SimpleGraph(
      name = "G",
      objects = Some(List(Node(nodeA), Node(nodeB), Node(nodeC), Cluster(cluster))),
      edges = Some(List(edgeAB, edgeBC))
    )

    val result   = SimpleGraphConverter.graphToDotString(graph)
    val expected = """digraph "G" {
  subgraph "cluster_1" {
    graph [label="Group 1"];
    "a" [label="A"];
    "b" [label="B"];
  }
  "c" [label="C"];
  "a" -> "b" [label="internal"];
  "b" -> "c" [label="external"];
}"""

    assertEquals(result, expected)
  }

  test("graphToDotString should handle nested subgraphs") {
    // Note: SimpleGraph doesn't support nested subgraphs in its structure.
    // We can simulate it by having two separate clusters where one conceptually contains the other
    val nodeA = SimpleGraphNode(
      _gvid = 0,
      name = "a",
      label = "a"
    )
    val nodeB = SimpleGraphNode(
      _gvid = 1,
      name = "b",
      label = "b"
    )
    val nodeC = SimpleGraphNode(
      _gvid = 2,
      name = "c",
      label = "c"
    )

    val outerCluster = SimpleGraphCluster(
      _gvid = 100.0,
      name = "outer",
//      bb = "0,0,100,100",
      nodes = List(0),
      label = "Outer Group"
    )

    val innerCluster = SimpleGraphCluster(
      _gvid = 101.0,
      name = "inner",
//      bb = "0,0,50,50",
      nodes = List(1),
      label = "Inner Group"
    )

    val graph = SimpleGraph(
      name = "G",
      objects = Some(List(Node(nodeA), Node(nodeB), Node(nodeC), Cluster(outerCluster), Cluster(innerCluster)))
    )

    val result   = SimpleGraphConverter.graphToDotString(graph)
    val expected = """digraph "G" {
  subgraph "cluster_outer" {
    graph [label="Outer Group"];
    "a" [label="a"];
  }
  subgraph "cluster_inner" {
    graph [label="Inner Group"];
    "b" [label="b"];
  }
  "c" [label="c"];
}"""

    assertEquals(result, expected)
  }

  test("graphToDotString should handle undirected graphs") {
    val nodeA = SimpleGraphNode(
      _gvid = 0,
      name = "a",
      label = "a"
    )
    val nodeB = SimpleGraphNode(
      _gvid = 1,
      name = "b",
      label = "b"
    )
    val edge = SimpleGraphEdge(
      _gvid = 0,
      tail = 0,
      head = 1
    )

    val graph = SimpleGraph(
      name = "G",
      directed = false,
      objects = Some(List(Node(nodeA), Node(nodeB))),
      edges = Some(List(edge))
    )

    val result   = SimpleGraphConverter.graphToDotString(graph)
    val expected = """graph "G" {
  "a" [label="a"];
  "b" [label="b"];
  "a" -- "b";
}"""

    assertEquals(result, expected)
  }

  test("graphToDotString should handle edges with ports") {
    val nodeA = SimpleGraphNode(
      _gvid = 0,
      name = "a",
      label = "a"
    )
    val nodeB = SimpleGraphNode(
      _gvid = 1,
      name = "b",
      label = "b"
    )

    val edge = SimpleGraphEdge(
      _gvid = 0,
      tail = 0,
      head = 1,
      tailport = Some("out"),
      headport = Some("in")
    )

    val graph = SimpleGraph(
      name = "G",
      objects = Some(List(Node(nodeA), Node(nodeB))),
      edges = Some(List(edge))
    )

    val result   = SimpleGraphConverter.graphToDotString(graph)
    val expected = """digraph "G" {
  "a" [label="a"];
  "b" [label="b"];
  "a":"out" -> "b":"in";
}"""

    assertEquals(result, expected)
  }

  test("graphToDotString should handle numeric node references in edges") {
    // Test edge with numeric tail/head that references gvids
    val nodeA = SimpleGraphNode(
      _gvid = 0,
      name = "nodeA",
      label = "nodeA"
    )
    val nodeB = SimpleGraphNode(
      _gvid = 1,
      name = "nodeB",
      label = "nodeB"
    )

    val edge = SimpleGraphEdge(
      _gvid = 0,
      tail = 0,
      head = 1
    )

    val graph = SimpleGraph(
      name = "G",
      objects = Some(List(Node(nodeA), Node(nodeB))),
      edges = Some(List(edge))
    )

    val result   = SimpleGraphConverter.graphToDotString(graph)
    val expected = """digraph "G" {
  "nodeA" [label="nodeA"];
  "nodeB" [label="nodeB"];
  "nodeA" -> "nodeB";
}"""

    assertEquals(result, expected)
  }

  test("graphToDotString should handle single attribute formatting") {
    val node = SimpleGraphNode(
      _gvid = 0,
      name = "single",
      label = "Single Attr"
    )

    val graph = SimpleGraph(
      name = "G",
      objects = Some(List(Node(node)))
    )

    val result   = SimpleGraphConverter.graphToDotString(graph)
    val expected = """digraph "G" {
  "single" [label="Single Attr"];
}"""

    assertEquals(result, expected)
  }

  test("graphToDotString should handle complex mixed graph") {
    val nodeA = SimpleGraphNode(
      _gvid = 0,
      name = "a",
      label = "Start",
      shape = Some("ellipse")
    )
    val nodeB = SimpleGraphNode(
      _gvid = 1,
      name = "b",
      label = "Middle",
      shape = Some("box")
    )
    val nodeC = SimpleGraphNode(
      _gvid = 2,
      name = "c",
      label = "End"
    )

    val cluster = SimpleGraphCluster(
      _gvid = 100.0,
      name = "process",
//      bb = "0,0,100,100",
      nodes = List(1),
      label = "Process",
      style = Some("filled")
    )

    val edgeAB = SimpleGraphEdge(
      _gvid = 0,
      tail = 0,
      head = 1,
      label = Some("first"),
      color = Some("red")
    )
    val edgeBC = SimpleGraphEdge(
      _gvid = 1,
      tail = 1,
      head = 2,
      label = Some("second")
    )

    val graph = SimpleGraph(
      name = "ComplexGraph",
      rankdir = Some("LR"),
      objects = Some(List(Node(nodeA), Node(nodeB), Node(nodeC), Cluster(cluster))),
      edges = Some(List(edgeAB, edgeBC))
    )

    val result   = SimpleGraphConverter.graphToDotString(graph)
    val expected = """digraph "ComplexGraph" {
  graph [rankdir="LR"];
  subgraph "cluster_process" {
    graph [label="Process", style="filled"];
    "b" [label="Middle", shape="box"];
  }
  "a" [label="Start", shape="ellipse"];
  "c" [label="End"];
  "a" -> "b" [label="first", color="red"];
  "b" -> "c" [label="second"];
}"""

    assertEquals(result, expected)
  }

  test("graphToDotString should support round-trip conversion consistency") {
    // Simple round-trip test: ViewerGraphElements -> SimpleGraph -> DOT string
    import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
    import org.jpablo.graphexplorer.viewer.models.{Attributes as ViewerAttributes, *}

    // Create simple ViewerGraphElements
    val node1 = ViewerNode.nodeNoDefaults(
      NodeId("node1"),
      ViewerAttributes(VectorMap(
        AttributeId("label") -> AttrValue("Node 1"),
        AttributeId("shape") -> AttrValue("box")
      ))
    )
    val node2 = ViewerNode.nodeNoDefaults(
      NodeId("node2"),
      ViewerAttributes(VectorMap(
        AttributeId("label") -> AttrValue("Node 2")
      ))
    )

    val arrow = Arrow(
      source = NodeId("node1"),
      target = NodeId("node2"),
      seq = 0,
      attributes = ViewerAttributes(VectorMap(
        AttributeId("label") -> AttrValue("connects"),
        AttributeId("color") -> AttrValue("red")
      ))
    )

    val group = ViewerGroup.group(
      GroupId("cluster1"),
      ViewerAttributes(VectorMap(
        AttributeId("label") -> AttrValue("Group 1"),
        AttributeId("style") -> AttrValue("filled")
      ))
    )

    val elements = ViewerGraphElements(
      nodes = VectorMap(NodeId("node1") -> node1, NodeId("node2") -> node2),
      arrows = Map(ArrowId("node1->node2/0") -> arrow),
      memberships = VectorMap(NodeId("node1") -> GroupId("cluster1")),
      groups = Map(GroupId("cluster1") -> group),
      graphAttributes = ViewerAttributes(VectorMap(
        AttributeId("rankdir") -> AttrValue("LR")
      ))
    )

    // Convert to SimpleGraph
    val simpleGraph = SimpleGraphConverter.fromViewerGraphElements(elements)

    // Convert to DOT string
    val dotString = SimpleGraphConverter.graphToDotString(simpleGraph)

    // Verify the DOT string contains expected elements
    assert(dotString.contains("digraph"), "Generated DOT should be a digraph")
    assert(dotString.contains("rankdir=\"LR\""), "Graph attribute should be preserved")
    assert(dotString.contains("\"node1\""), "Node 1 should be present")
    assert(dotString.contains("\"node2\""), "Node 2 should be present")
    assert(dotString.contains("label=\"Node 1\""), "Node 1 label should be preserved")
    assert(dotString.contains("label=\"Node 2\""), "Node 2 label should be preserved")
    assert(dotString.contains("shape=\"box\""), "Node 1 shape should be preserved")
    assert(dotString.contains("->"), "Directed edge should be present")
    assert(dotString.contains("label=\"connects\""), "Edge label should be preserved")
    assert(dotString.contains("color=\"red\""), "Edge color should be preserved")
    assert(dotString.contains("cluster_cluster1"), "Cluster should be present")
    assert(dotString.contains("label=\"Group 1\""), "Cluster label should be preserved")
    assert(dotString.contains("style=\"filled\""), "Cluster style should be preserved")

    // Convert back to ViewerGraphElements
    val backConverted = SimpleGraphConverter.toViewerGraphElements(simpleGraph)

    // Verify basic structure is preserved
    assertEquals(backConverted.nodes.size, elements.nodes.size, "Node count should be preserved")
    assertEquals(backConverted.arrows.size, elements.arrows.size, "Arrow count should be preserved")
    assertEquals(backConverted.groups.size, elements.groups.size, "Group count should be preserved")

    // Verify node attributes
    elements.nodes.foreach { case (nodeId, node) =>
      assert(backConverted.nodes.contains(nodeId), s"Node $nodeId should be preserved")
      val backNode = backConverted.nodes(nodeId)

      node.attributes.values.foreach { case (attrId, attrValue) =>
        val backValue = backNode.attributes.values.get(attrId)
        assertEquals(backValue, Some(attrValue), s"Node $nodeId attribute $attrId should be preserved")
      }
    }

    // Verify arrow attributes
    elements.arrows.foreach { case (arrowId, arrow) =>
      assert(backConverted.arrows.contains(arrowId), s"Arrow $arrowId should be preserved")
      val backArrow = backConverted.arrows(arrowId)

      assertEquals(backArrow.source, arrow.source, "Arrow source should be preserved")
      assertEquals(backArrow.target, arrow.target, "Arrow target should be preserved")

      arrow.attributes.values.foreach { case (attrId, attrValue) =>
        val backValue = backArrow.attributes.values.get(attrId)
        assertEquals(backValue, Some(attrValue), s"Arrow $arrowId attribute $attrId should be preserved")
      }
    }
  }

  test("graphToDotString should handle complex graph with multiple clusters and edges") {
    val graph = SimpleGraph(
      name = "G",
      objects = Some(List(
        Cluster(SimpleGraphCluster(
          _gvid = 0,
          name = "g5294cce8",
          nodes = List(3, 4),
          label = "group 1",
          edges = Some(List(0)),
          subgraphs = Some(List(1, 2)),
          lheight = Some("0.23"),
          lp = Some("51,198"),
          lwidth = Some("0.60"),
          cluster = Some("true")
        )),
        Cluster(SimpleGraphCluster(
          _gvid = 1,
          name = "g863fd476",
          nodes = List(3),
          label = "group 2",
          lheight = Some("0.23"),
          lp = Some("51,80.4"),
          lwidth = Some("0.60"),
          cluster = Some("true")
        )),
        Cluster(SimpleGraphCluster(
          _gvid = 2,
          name = "geca09c80",
          nodes = List(4),
          label = "group 3",
          lheight = Some("0.23"),
          lp = Some("51,165.2"),
          lwidth = Some("0.60"),
          cluster = Some("true")
        )),
        Node(SimpleGraphNode(
          _gvid = 3,
          name = "c",
          label = "c",
          pos = Some("51,42"),
          height = Some("0.5"),
          width = Some("0.75")
        )),
        Node(SimpleGraphNode(
          _gvid = 4,
          name = "b",
          label = "b",
          pos = Some("51,126.8"),
          height = Some("0.5"),
          width = Some("0.75")
        )),
        Node(SimpleGraphNode(
          _gvid = 5,
          name = "a",
          label = "a",
          pos = Some("51,236.4"),
          height = Some("0.5"),
          width = Some("0.75")
        ))
      )),
      edges = Some(List(
        SimpleGraphEdge(_gvid = 1, tail = 5, head = 4, pos = Some("e,51,145.04 51,218.11 51,201.48 51,176.05 51,156.4")),
        SimpleGraphEdge(_gvid = 0, tail = 4, head = 3, pos = Some("e,51,60.419 51,108.64 51,97.947 51,83.915 51,71.572"))
      )),
      label = Some("")
    )

    val expected =
      """digraph "G" {
        |    graph [label=""];
        |    subgraph "g5294cce8" {
        |        graph [
        |            label="group 1",
        |            lheight="0.23",
        |            lp="51,198",
        |            lwidth="0.60",
        |            cluster="true"
        |        ];
        |        subgraph "g863fd476" {
        |            graph [
        |                label="group 2",
        |                lheight="0.23",
        |                lp="51,80.4",
        |                lwidth="0.60",
        |                cluster="true"
        |            ];
        |            "c" [
        |                label="c",
        |                pos="51,42",
        |                height="0.5",
        |                width="0.75"
        |            ];
        |        }
        |        subgraph "geca09c80" {
        |            graph [
        |                label="group 3",
        |                lheight="0.23",
        |                lp="51,165.2",
        |                lwidth="0.60",
        |                cluster="true"
        |            ];
        |            "b" [
        |                label="b",
        |                pos="51,126.8",
        |                height="0.5",
        |                width="0.75"
        |            ];
        |        }
        |    }
        |    "a" [
        |        label="a",
        |        pos="51,236.4",
        |        height="0.5",
        |        width="0.75"
        |    ];
        |    "a" -> "b";
        |    "b" -> "c";
        |}""".stripMargin

    val result = SimpleGraphConverter.graphToDotString(graph)

    assertEquals(result, expected)
  }

  test("GraphDoTotSpec should handle html labels") {
    val jsonGraph =
      """{"name":"G","objects":[{"_gvid":0,"name":"task_menu","label":"\n<table border=\"1\" cellborder=\"0\" cellspacing=\"1\">\n<tr><td align=\"left\"><b>Task 1</b></td></tr>\n<tr><td align=\"left\">Choose Menu</td></tr>\n<tr><td align=\"left\"><font color=\"darkgreen\">done</font></td></tr>\n</table>","pos":"53.879,110.2","height":"1.0611","width":"1.4189","shape":"plaintext"},{"_gvid":1,"name":"task_ingredients","label":"\\N","pos":"53.879,18","height":"0.5","width":"1.4966","shape":"plaintext"}],"edges":[{"_gvid":0,"tail":0,"head":1,"pos":"e,53.879,35.809 53.879,72.252 53.879,63.852 53.879,55.061 53.879,47.099"}]}"""
    val graph = read[SimpleGraph](jsonGraph)

    val result = SimpleGraphConverter.graphToDotString(graph)

    val expected =
      """digraph "G" {
        |    "task_menu" [
        |        label=<
        |              <table border="1" cellborder="0" cellspacing="1">
        |              <tr><td align="left"><b>Task 1</b></td></tr>
        |              <tr><td align="left">Choose Menu</td></tr>
        |              <tr><td align="left"><font color="darkgreen">done</font></td></tr>
        |              </table>
        |        >, 
        |        pos="53.879,110.2", 
        |        height="1.0611", 
        |        width="1.4189", 
        |        shape="plaintext"
        |    ];
        |    "task_ingredients" [
        |        label="\N", 
        |        pos="53.879,18", 
        |        height="0.5", 
        |        width="1.4966", 
        |        shape="plaintext"
        |    ];
        |    "task_menu" -> "task_ingredients";
        |}""".stripMargin

    assertEquals(result, expected)
  }
