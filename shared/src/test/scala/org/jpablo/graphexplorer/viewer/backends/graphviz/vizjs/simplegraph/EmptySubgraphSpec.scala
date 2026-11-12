package org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.models.{GroupId, NodeId}
import upickle.default.*

/** An empty subgraph is a group, not a node.
  *
  * Graphviz lists each subgraph in `objects` before the nodes, and
  * `_subgraph_cnt` gives their number. It writes the key `nodes` only for a
  * subgraph that has nodes. The decoder of `SimpleGraphObject` read an object
  * as a cluster only when it had `nodes` or `cluster`. Thus it read an empty
  * subgraph as a node, with the name and the label of the subgraph. A save
  * then wrote `"cluster_legend" [label="Legend"];` into the file, with no
  * warning. The desktop and `gx` both read DOT through this decoder.
  */
class EmptySubgraphSpec extends FunSuite:

  // digraph G {
  //   subgraph cluster_legend { label="Legend" }
  //   subgraph plain {}
  //   a
  // }
  //
  // produced with: dot -Tjson0 (graphviz 14.1.5)
  private val graphJson =
    """|{
       |  "name": "G",
       |  "directed": true,
       |  "strict": false,
       |  "bb": "0,0,54,36",
       |  "label": "",
       |  "_subgraph_cnt": 2,
       |  "objects": [
       |    {
       |      "name": "cluster_legend",
       |      "label": "Legend",
       |      "_gvid": 0
       |    },
       |    {
       |      "name": "plain",
       |      "label": "",
       |      "_gvid": 1
       |    },
       |    {
       |      "_gvid": 2,
       |      "name": "a",
       |      "height": "0.5",
       |      "label": "\\N",
       |      "pos": "27,18",
       |      "width": "0.75"
       |    }
       |  ]
       |}""".stripMargin

  test("an empty cluster and an empty subgraph are groups, and only the real node is a node") {
    val graph = toViewerGraph(read[SimpleGraph](graphJson))
    assertEquals(graph.nodes.keySet.toSet, Set(NodeId("a")))
    assertEquals(graph.groups.keySet.toSet, Set(GroupId("legend"), GroupId("plain")))
  }
