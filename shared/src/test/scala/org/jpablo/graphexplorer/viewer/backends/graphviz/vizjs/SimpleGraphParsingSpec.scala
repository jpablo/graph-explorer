package org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.{SimpleGraph, SimpleGraphObject}
import upickle.default.*

class SimpleGraphParsingSpec extends FunSuite:

  // Original DOT input:
  // graph ER {
  //    fontname="Helvetica,Arial,sans-serif"
  //    node [fontname="Helvetica,Arial,sans-serif"]
  //    edge [fontname="Helvetica,Arial,sans-serif"]
  //    layout=neato
  //    node [shape=box]; course; institute; student;
  //    node [shape=ellipse]; {node [label="name"] name0; name1; name2;}
  //    code; grade; number;
  // }

  // json0 output from viz.js:

  val json0response = """{
    "name": "ER",
    "directed": false,
    "strict": false,
    "bb": "0,0,253.96,281",
    "fontname": "Helvetica,Arial,sans-serif",
    "layout": "neato",
    "_subgraph_cnt": 1,
    "objects": [
      { "name": "%1", "fontname": "Helvetica,Arial,sans-serif", "layout": "neato", "_gvid": 0, "nodes": [ 4,5,6 ] },
      { "_gvid": 1, "name": "course", "fontname": "Helvetica,Arial,sans-serif", "height": "0.5", "label": "\\N", "pos": "118,18", "shape": "box", "width": "0.75" },
      { "_gvid": 2, "name": "institute", "fontname": "Helvetica,Arial,sans-serif", "height": "0.5", "label": "\\N", "pos": "156.56,179", "shape": "box", "width": "0.84875" },
      { "_gvid": 3, "name": "student", "fontname": "Helvetica,Arial,sans-serif", "height": "0.5", "label": "\\N", "pos": "133.22,263", "shape": "box", "width": "0.78391" },
      { "_gvid": 4, "name": "name0", "fontname": "Helvetica,Arial,sans-serif", "height": "0.5", "label": "name", "pos": "185.95,81", "shape": "ellipse", "width": "0.88747" },
      { "_gvid": 5, "name": "name1", "fontname": "Helvetica,Arial,sans-serif", "height": "0.5", "label": "name", "pos": "66.949,200", "shape": "ellipse", "width": "0.88747" },
      { "_gvid": 6, "name": "name2", "fontname": "Helvetica,Arial,sans-serif", "height": "0.5", "label": "name", "pos": "31.949,53", "shape": "ellipse", "width": "0.88747" },
      { "_gvid": 7, "name": "code", "fontname": "Helvetica,Arial,sans-serif", "height": "0.5", "label": "\\N", "pos": "50.266,263", "shape": "ellipse", "width": "0.81294" },
      { "_gvid": 8, "name": "grade", "fontname": "Helvetica,Arial,sans-serif", "height": "0.5", "label": "\\N", "pos": "221.48,242", "shape": "ellipse", "width": "0.90227" },
      { "_gvid": 9, "name": "number", "fontname": "Helvetica,Arial,sans-serif", "height": "0.5", "label": "\\N", "pos": "82.535,116", "shape": "ellipse", "width": "1.126" }
    ]
  }"""

  test("SimpleGraph should parse ER graph structure correctly") {
    val graph = read[SimpleGraph](json0response)

    // Verify graph properties
    assertEquals(graph.name, "ER")
    assertEquals(graph.directed, false, "Graph should be undirected")
    assertEquals(graph.fontname, Some("Helvetica,Arial,sans-serif"))
    assertEquals(graph.layout, Some("neato"))

    // Verify all nodes are present
    val nodes = graph.objects.map(_.collect {
      case SimpleGraphObject.Node(node) => node
    }).getOrElse(List())

    assertEquals(nodes.length, 9, "Should have 9 nodes")

    // There's also a cluster object (subgraph is represented as cluster)
    val clusters = graph.objects.map(_.collect {
      case SimpleGraphObject.Cluster(cluster) => cluster
    }).getOrElse(List())
    assertEquals(clusters.length, 1, "Should have 1 cluster/subgraph")

    // Verify entity nodes (box shape)
    val entityNodes = nodes.filter(_.shape.contains("box"))
    assertEquals(entityNodes.length, 3, "Should have 3 entity nodes")
    val entityNames = entityNodes.map(_.name).toSet
    assertEquals(entityNames, Set("course", "institute", "student"))

    // Verify attribute nodes (ellipse shape)
    val attributeNodes = nodes.filter(_.shape.contains("ellipse"))
    assertEquals(attributeNodes.length, 6, "Should have 6 attribute nodes")

    // Verify name nodes have custom label
    val nameNodes = nodes.filter(_.name.startsWith("name"))
    assertEquals(nameNodes.length, 3, "Should have 3 name nodes")
    nameNodes.foreach { node =>
      assertEquals(node.label, "name", s"Node ${node.name} should have label 'name'")
    }

    // Verify other attribute nodes
    val otherAttributeNames = attributeNodes.filter(!_.name.startsWith("name")).map(_.name).toSet
    assertEquals(otherAttributeNames, Set("code", "grade", "number"))

    // Verify that labels use \N for default
    val codeNode = nodes.find(_.name == "code").get
    assertEquals(codeNode.label, "\\N", "Code node should have \\N label")

    // Verify all nodes have the correct fontname
    nodes.foreach { node =>
      assertEquals(node.fontname, Some("Helvetica,Arial,sans-serif"))
    }
  }
