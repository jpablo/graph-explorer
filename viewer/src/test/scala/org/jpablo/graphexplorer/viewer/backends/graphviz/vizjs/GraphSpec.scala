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

  test("getEdgePos should extract edge positions from graph JSON with three arrow variants") {
    val graph: Graph  = JSON.parse(sampleJson).asInstanceOf[Graph]
    val edgePositions = Graph.getEdgePos(graph)

    assertEquals(edgePositions.size, 3)

    // Arrow 1: explicit start and end with box arrowtail
    val arrow1 = edgePositions("a->b/1")
    assertEquals(arrow1.startPoint, Point(48.41, 6.3984))
    assertEquals(arrow1.endPoint, Point(89.687, 5.5202))
    assertEquals(arrow1.controlPoints, List(Point(58.505, 5.081), Point(65.01, 4.504), Point(71.952, 4.3568), Point(78.68, 4.6392)))

    // Arrow 2: no markers, all control points (no special glyphs)
    val arrow2 = edgePositions("a->b/2")
    assertEquals(arrow2.startPoint, Point(54.403, 18))
    assertEquals(arrow2.endPoint, Point(89.616, 18))
    assertEquals(arrow2.controlPoints, List(Point(65.541, 18), Point(78.48, 18)))

    // Arrow 3: explicit start only with odot arrowtail
    val arrow3 = edgePositions("a->b/3")
    assertEquals(arrow3.startPoint, Point(48.41, 29.602))
    assertEquals(arrow3.endPoint, Point(89.687, 30.48))
    assertEquals(arrow3.controlPoints, List(Point(57.228, 30.8), Point(67.713, 31.83), Point(79.464, 31.723)))
  }

  test("getEdgePos should handle empty edges") {
    val emptyGraph: Graph = JSON.parse("""{"name": "empty", "edges": []}""").asInstanceOf[Graph]
    val edgePositions     = Graph.getEdgePos(emptyGraph)

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
          "pos": "10.0,20.0 30.0,40.0"
        }
      ]
    }"""

    val graph: Graph  = JSON.parse(graphWithoutId).asInstanceOf[Graph]
    val edgePositions = Graph.getEdgePos(graph)

    assertEquals(edgePositions.size, 1)
    val arrowPos = edgePositions("nodeA->nodeB")
    assertEquals(arrowPos.startPoint, Point(10.0, 20.0))
    assertEquals(arrowPos.endPoint, Point(30.0, 40.0))
    assertEquals(arrowPos.controlPoints, List.empty)
  }

  test("ArrowPositionParser should parse position with explicit start and end") {
    val pos    = "s,52.513,11.877 e,89.772,11.765 62.737,11.385 67.943,11.231"
    val result = ArrowPositionParser.parse(pos)

    assert(result.isDefined)
    val arrowPos = result.get
    assertEquals(arrowPos.startPoint, Point(52.513, 11.877))
    assertEquals(arrowPos.endPoint, Point(89.772, 11.765))
    assertEquals(arrowPos.controlPoints, List(Point(62.737, 11.385), Point(67.943, 11.231)))
  }

  test("ArrowPositionParser should parse position with only end marker") {
    val pos    = "e,89.772,24.235 52.513,24.123 60.666,24.683 69.937,24.868"
    val result = ArrowPositionParser.parse(pos)

    assert(result.isDefined)
    val arrowPos = result.get
    assertEquals(arrowPos.startPoint, Point(52.513, 24.123))
    assertEquals(arrowPos.endPoint, Point(89.772, 24.235))
    assertEquals(arrowPos.controlPoints, List(Point(60.666, 24.683), Point(69.937, 24.868)))
  }
