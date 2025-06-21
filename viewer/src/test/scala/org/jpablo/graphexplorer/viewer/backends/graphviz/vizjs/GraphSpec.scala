package org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs

import munit.FunSuite
import scala.scalajs.js.JSON

class GraphSpec extends FunSuite:

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
      "pos": "s,54.403,18 e,89.616,18 64.705,18 69.276,18 73.956,18 78.539,18"
    }
  ]
}"""

  test("getEdgePos should extract edge positions from graph JSON") {
    val graph: Graph = JSON.parse(sampleJson).asInstanceOf[Graph]
    val edgePositions = Graph.getEdgePos(graph)
    
    assertEquals(edgePositions.size, 1)
    assertEquals(edgePositions("arrow:a->b/1"), "s,54.403,18 e,89.616,18 64.705,18 69.276,18 73.956,18 78.539,18")
  }

  test("getEdgePos should handle empty edges") {
    val emptyGraph: Graph = JSON.parse("""{"name": "empty", "edges": []}""").asInstanceOf[Graph]
    val edgePositions = Graph.getEdgePos(emptyGraph)
    
    assertEquals(edgePositions.size, 0)
  }

  test("getEdgePos should create fallback edge ID when no id provided") {
    val graphWithoutId = """{
      "objects": [
        {"_gvid": 0, "name": "nodeA"},
        {"_gvid": 1, "name": "nodeB"}
      ],
      "edges": [
        {
          "tail": 0,
          "head": 1,
          "pos": "test,pos,data"
        }
      ]
    }"""
    
    val graph: Graph = JSON.parse(graphWithoutId).asInstanceOf[Graph]
    val edgePositions = Graph.getEdgePos(graph)
    
    assertEquals(edgePositions.size, 1)
    assertEquals(edgePositions("nodeA->nodeB"), "test,pos,data")
  }