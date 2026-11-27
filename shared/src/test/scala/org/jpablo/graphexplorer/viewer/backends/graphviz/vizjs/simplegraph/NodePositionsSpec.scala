package org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph

import munit.FunSuite
import upickle.default.read

/** The centers of the nodes of a Graphviz render, from its `json0` output.
  *
  * Freeze reads the current place of each node here (ADR 0004). A subgraph has
  * a box and no `pos`, so it is not a node place.
  */
class NodePositionsSpec extends FunSuite:

  // A part of the json0 of `digraph { subgraph cluster_x { a } "a b" -> a }`, from viz-js.
  private val json0 =
    """{
      |  "name": "G", "directed": true, "strict": false, "bb": "0,0,154,124",
      |  "_subgraph_cnt": 1,
      |  "objects": [
      |    {"_gvid": 0, "name": "cluster_x", "bb": "8,8,78,62", "nodes": [1], "edges": [], "label": ""},
      |    {"_gvid": 1, "name": "a", "label": "\\N", "pos": "43,35", "width": "0.75", "height": "0.5"},
      |    {"_gvid": 2, "name": "a b", "label": "\\N", "pos": "117.5,106.25", "width": "0.75", "height": "0.5"},
      |    {"_gvid": 3, "name": "no-pos", "label": "\\N"}
      |  ],
      |  "edges": [{"_gvid": 0, "tail": 2, "head": 1, "pos": "e,50,52 110,90 90,70 60,60"}]
      |}""".stripMargin

  test("each node with a pos gives its center, in points, with the y axis up"):
    val graph = read[SimpleGraph](json0)
    assertEquals(getNodePos(graph), Map("a" -> Point(43, 35), "a b" -> Point(117.5, 106.25)))

  test("a graph with no objects gives no positions"):
    assertEquals(getNodePos(read[SimpleGraph]("""{"name": "G", "directed": true, "strict": false}""")), Map.empty)
