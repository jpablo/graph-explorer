package org.jpablo.graphexplorer.viewer.graph

import munit.ScalaCheckSuite
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.AttrStatus.Single

class ViewerGraphSpec extends ScalaCheckSuite:

  val rootId = ViewerGraph.defaultRootId
  val a = NodeId("a")
  val b = NodeId("b")
  val c = NodeId("c")

  test("addEdge should add an edge between two nodes") {
    val graph =
      ViewerGraph(
        rootId.value,
        ViewerGraphData(
          rootId      = rootId,
          arrows      = Map.empty,
          groups      = Map(rootId -> ViewerGroup(rootId)),
          nodes       = Map(a -> ViewerNode(a), b -> ViewerNode(b), c -> ViewerNode(c)),
          memberships = Map.empty
        ),
        "digraph"
      )

    val edgeId = NodeId("a->b:1")
    val expected =
      ViewerGraph(
        "G",
        ViewerGraphData(
          rootId      = rootId,
          arrows      = Map(edgeId -> Arrow(a, b, seq = 1)),
          groups      = Map(rootId -> ViewerGroup(rootId)),
          nodes       = Map(a -> ViewerNode(a), b -> ViewerNode(b), c -> ViewerNode(c)),
          memberships = Map.empty
        ),
        "digraph"
      )

    val updated = graph.addEdge(a, b)._1

    assertEquals(updated, expected)
  }

  test("updateAttributes should update the attributes of an edge") {
    val edgeId = NodeId("a->b:0")
    val graph =
      ViewerGraph(
        "G",
        ViewerGraphData(
          rootId      = rootId,
          arrows      = Map(edgeId -> Arrow(a, b, Attributes(Map("id" -> AttrValue("1"))), 0)),
          groups      = Map(rootId -> ViewerGroup(rootId)),
          nodes       = Map(a -> ViewerNode(a), b -> ViewerNode(b)),
          memberships = Map.empty
        ),
        "digraph"
      )
    val expected =
      ViewerGraph(
        "G",
        ViewerGraphData(
          rootId = rootId,
          arrows =
            Map(edgeId -> Arrow(a, b, Attributes(Map("id" -> AttrValue("1"), "style" -> AttrValue("dashed"))), 0)),
          groups      = Map(rootId -> ViewerGroup(rootId)),
          nodes       = Map(a -> ViewerNode(a), b -> ViewerNode(b)),
          memberships = Map.empty
        ),
        "digraph"
      )

    val updated = graph.updateAttributes(Set(edgeId), AttributesUpdates(Map("style" -> Single(AttrValue("dashed")))))
    pprint.log(updated)
    assertEquals(updated, expected)
  }

  test("removeNodes should remove the nodes and their edges") {
    val edgeId1 = NodeId("a->b:0")
    val edgeId2 = NodeId("b->c:0")
    val graph =
      ViewerGraph(
        "G",
        ViewerGraphData(
          rootId = rootId,
          arrows = Map(
            edgeId1 -> Arrow(a, b, seq = 0),
            edgeId2 -> Arrow(b, c, seq = 0)
          ),
          groups = Map(rootId -> ViewerGroup(rootId)),
          nodes = Map(
            a -> ViewerNode(a),
            b -> ViewerNode(b),
            c -> ViewerNode(c)
          ),
          memberships = Map.empty
        ),
        "digraph"
      )

    val expected =
      ViewerGraph(
        "G",
        ViewerGraphData(
          rootId = rootId,
          arrows = Map.empty,
          groups = Map(rootId -> ViewerGroup(rootId)),
          nodes = Map(
            a -> ViewerNode(a),
            c -> ViewerNode(c)
          ),
          memberships = Map.empty
        ),
        "digraph"
      )

    val updated = graph.removeNodes(Set(b))
    assertEquals(updated, expected)
  }

  test("removeNodes a single arrow") {
    val edgeId1 = NodeId("a->b:0")
    val edgeId2 = NodeId("a->b:1")
    val graph =
      ViewerGraph(
        "G",
        ViewerGraphData(
          rootId = rootId,
          arrows = Map(
            edgeId1 -> Arrow(a, b, seq = 0),
            edgeId2 -> Arrow(a, b, seq = 1)
          ),
          groups = Map(rootId -> ViewerGroup(rootId)),
          nodes = Map(
            a -> ViewerNode(a),
            b -> ViewerNode(b)
          ),
          memberships = Map.empty
        ),
        "digraph"
      )

    val expected =
      ViewerGraph(
        "G",
        ViewerGraphData(
          rootId = rootId,
          arrows = Map(
            edgeId2 -> Arrow(a, b, seq = 1)
          ),
          groups = Map(rootId -> ViewerGroup(rootId)),
          nodes = Map(
            a -> ViewerNode(a),
            b -> ViewerNode(b)
          ),
          memberships = Map.empty
        ),
        "digraph"
      )

    val updated = graph.removeNodes(Set(edgeId1))

    assertEquals(updated, expected)
  }

end ViewerGraphSpec
