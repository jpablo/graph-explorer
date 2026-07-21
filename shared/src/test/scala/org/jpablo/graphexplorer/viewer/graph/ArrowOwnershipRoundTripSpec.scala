package org.jpablo.graphexplorer.viewer.graph

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.{SimpleGraph, toViewerGraph}
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph.viewerGraphToText
import org.jpablo.graphexplorer.viewer.models.ArrowId
import upickle.default.read

/** Arrows must round-trip in the subgraph they were DECLARED in, and nested
  * clusters must serialize in declaration (_gvid) order. Layout engines like
  * fdp lay clusters out separately, so an intra-cluster edge re-serialized at
  * top level -- or clusters emitted in Map order -- changes the whole drawing
  * (the "wrong ownership of arrows" library diagram: detail page and
  * thumbnail rendered different layouts from the same source).
  *
  * The fixture is the viz-js (fdp) dot_json of that diagram; with these
  * assertions green, the fdp layout of the round-tripped text was verified
  * IDENTICAL to the original source (per-node positions + bb).
  */
class ArrowOwnershipRoundTripSpec extends FunSuite:

  private val dotJson = "{\n  \"name\": \"g\",\n  \"directed\": false,\n  \"strict\": false,\n  \"bb\": \"0 0 170 241\",\n  \"bgcolor\": \"#ffffff00\",\n  \"label\": \"\",\n  \"layout\": \"fdp\",\n  \"overlap\": \"scale\",\n  \"start\": \"2\",\n  \"_subgraph_cnt\": 3,\n  \"objects\": [\n    {\n      \"name\": \"cluster_G0\",\n      \"bgcolor\": \"#ffffff00\",\n      \"label\": \"G\u222aT\",\n      \"layout\": \"fdp\",\n      \"overlap\": \"scale\",\n      \"start\": \"2\",\n      \"_gvid\": 0,\n      \"subgraphs\": [\n        1,\n2\n      ],\n      \"nodes\": [\n        3,4,5,6,7,8,9\n      ],\n      \"edges\": [\n        0,2,1,4,5,6,7,3\n      ]\n    },\n    {\n      \"name\": \"cluster_G1\",\n      \"bgcolor\": \"white\",\n      \"label\": \"G\",\n      \"layout\": \"fdp\",\n      \"overlap\": \"scale\",\n      \"start\": \"2\",\n      \"_gvid\": 1,\n      \"nodes\": [\n        3,4,5\n      ],\n      \"edges\": [\n        0,2,1\n      ]\n    },\n    {\n      \"name\": \"cluster_G2\",\n      \"bgcolor\": \"white\",\n      \"label\": \"H\",\n      \"layout\": \"fdp\",\n      \"overlap\": \"scale\",\n      \"start\": \"2\",\n      \"_gvid\": 2,\n      \"nodes\": [\n        6,7,8,9\n      ],\n      \"edges\": [\n        4,5,6,7\n      ]\n    },\n    {\n      \"_gvid\": 3,\n      \"name\": \"node1\",\n      \"color\": \"black\",\n      \"fillcolor\": \"white\",\n      \"fixedsize\": \"true\",\n      \"fontsize\": \"12\",\n      \"label\": \"s\u2081\",\n      \"shape\": \"circle\",\n      \"style\": \"dashed\",\n      \"width\": \"0.25\"\n    },\n    {\n      \"_gvid\": 4,\n      \"name\": \"node2\",\n      \"color\": \"black\",\n      \"fillcolor\": \"white\",\n      \"fixedsize\": \"true\",\n      \"fontsize\": \"12\",\n      \"label\": \"s\u2082\",\n      \"shape\": \"circle\",\n      \"style\": \"filled\",\n      \"width\": \"0.25\"\n    },\n    {\n      \"_gvid\": 5,\n      \"name\": \"node3\",\n      \"color\": \"black\",\n      \"fillcolor\": \"white\",\n      \"fixedsize\": \"true\",\n      \"fontsize\": \"12\",\n      \"label\": \"s\u2083\",\n      \"shape\": \"circle\",\n      \"style\": \"filled\",\n      \"width\": \"0.25\"\n    },\n    {\n      \"_gvid\": 6,\n      \"name\": \"node5\",\n      \"color\": \"black\",\n      \"fillcolor\": \"white\",\n      \"fixedsize\": \"true\",\n      \"fontsize\": \"12\",\n      \"label\": \"t\u2082\",\n      \"shape\": \"circle\",\n      \"style\": \"filled\",\n      \"width\": \"0.25\"\n    },\n    {\n      \"_gvid\": 7,\n      \"name\": \"node6\",\n      \"color\": \"black\",\n      \"fillcolor\": \"white\",\n      \"fixedsize\": \"true\",\n      \"fontsize\": \"12\",\n      \"label\": \"t\u2083\",\n      \"shape\": \"circle\",\n      \"style\": \"filled\",\n      \"width\": \"0.25\"\n    },\n    {\n      \"_gvid\": 8,\n      \"name\": \"node4\",\n      \"color\": \"black\",\n      \"fillcolor\": \"white\",\n      \"fixedsize\": \"true\",\n      \"fontsize\": \"12\",\n      \"label\": \"t\u2081\",\n      \"shape\": \"circle\",\n      \"style\": \"filled\",\n      \"width\": \"0.25\"\n    },\n    {\n      \"_gvid\": 9,\n      \"name\": \"node7\",\n      \"color\": \"black\",\n      \"fillcolor\": \"white\",\n      \"fixedsize\": \"true\",\n      \"fontsize\": \"12\",\n      \"label\": \"t\u2084\",\n      \"shape\": \"circle\",\n      \"style\": \"filled\",\n      \"width\": \"0.25\"\n    }\n  ],\n  \"edges\": [\n    {\n      \"_gvid\": 0,\n      \"tail\": 3,\n      \"head\": 4,\n      \"arrowsize\": \"0.6\",\n      \"color\": \"black\",\n      \"fontsize\": \"8\",\n      \"forcelabels\": \"true\",\n      \"penwidth\": \"0.75\"\n    },\n    {\n      \"_gvid\": 2,\n      \"tail\": 5,\n      \"head\": 3,\n      \"arrowsize\": \"0.6\",\n      \"color\": \"black\",\n      \"fontsize\": \"8\",\n      \"forcelabels\": \"true\",\n      \"penwidth\": \"0.75\"\n    },\n    {\n      \"_gvid\": 1,\n      \"tail\": 4,\n      \"head\": 5,\n      \"arrowsize\": \"0.6\",\n      \"color\": \"black\",\n      \"fontsize\": \"8\",\n      \"forcelabels\": \"true\",\n      \"penwidth\": \"0.75\"\n    },\n    {\n      \"_gvid\": 4,\n      \"tail\": 6,\n      \"head\": 7,\n      \"arrowsize\": \"0.6\",\n      \"color\": \"black\",\n      \"fontsize\": \"8\",\n      \"forcelabels\": \"true\",\n      \"penwidth\": \"0.75\"\n    },\n    {\n      \"_gvid\": 5,\n      \"tail\": 7,\n      \"head\": 8,\n      \"arrowsize\": \"0.6\",\n      \"color\": \"black\",\n      \"fontsize\": \"8\",\n      \"forcelabels\": \"true\",\n      \"penwidth\": \"0.75\"\n    },\n    {\n      \"_gvid\": 6,\n      \"tail\": 7,\n      \"head\": 9,\n      \"arrowsize\": \"0.6\",\n      \"color\": \"black\",\n      \"fontsize\": \"8\",\n      \"forcelabels\": \"true\",\n      \"penwidth\": \"0.75\"\n    },\n    {\n      \"_gvid\": 7,\n      \"tail\": 8,\n      \"head\": 6,\n      \"arrowsize\": \"0.6\",\n      \"color\": \"black\",\n      \"fontsize\": \"8\",\n      \"forcelabels\": \"true\",\n      \"penwidth\": \"0.75\"\n    },\n    {\n      \"_gvid\": 3,\n      \"tail\": 5,\n      \"head\": 7,\n      \"arrowsize\": \"0.6\",\n      \"color\": \"black\",\n      \"fontsize\": \"8\",\n      \"forcelabels\": \"true\",\n      \"penwidth\": \"1\",\n      \"style\": \"dashed\"\n    }\n  ]\n}\n"

  test("arrow memberships: each edge owned by its innermost declaring subgraph"):
    val vg = toViewerGraph(read[SimpleGraph](dotJson))
    val am = vg.elements.arrowMemberships
    def owner(a: String): Option[String] = am.get(ArrowId(a)).map(_.value)
    assertEquals(owner("node1->node2/0"), Some("G1"))
    assertEquals(owner("node2->node3/1"), Some("G1"))
    assertEquals(owner("node3->node1/2"), Some("G1"))
    assertEquals(owner("node5->node6/4"), Some("G2"))
    assertEquals(owner("node6->node4/5"), Some("G2"))
    assertEquals(owner("node6->node7/6"), Some("G2"))
    assertEquals(owner("node4->node5/7"), Some("G2"))
    // the cross-cluster edge is owned by the OUTER cluster it was declared in
    assertEquals(owner("node3->node6/3"), Some("G0"))

  test("serializer: arrows emit inside their declaring cluster, clusters in _gvid order"):
    val vg  = toViewerGraph(read[SimpleGraph](dotJson))
    val out = viewerGraphToText(vg, omitInternal = false)
    def idx(s: String): Int =
      val i = out.indexOf(s); assert(i >= 0, s"not found: $s"); i
    val g1Open  = idx("subgraph \"G1\"")
    val g2Open  = idx("subgraph \"G2\"")
    // declaration order: G1 before G2 (memberships is a Map -- must be sorted)
    assert(g1Open < g2Open, "nested clusters must serialize in _gvid order")
    // G1 edges live between the G1 open and the G2 open
    for e <- List("\"node1\" -- \"node2\"", "\"node2\" -- \"node3\"", "\"node3\" -- \"node1\"") do
      val i = idx(e)
      assert(i > g1Open && i < g2Open, s"$e must serialize inside G1")
    // G2 edges after the G2 open
    for e <- List("\"node5\" -- \"node6\"", "\"node6\" -- \"node4\"") do
      assert(idx(e) > g2Open, s"$e must serialize inside G2")
    // the cross-cluster edge stays inside G0, after the nested clusters
    val cross = idx("\"node3\" -- \"node6\"")
    assert(cross > g2Open, "cross-cluster edge must serialize inside G0, after the nested clusters")

end ArrowOwnershipRoundTripSpec
