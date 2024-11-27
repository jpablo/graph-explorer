package org.jpablo.graphexplorer.viewer.graph

import munit.ScalaCheckSuite
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.models.*

import scala.collection.mutable

class ViewerGraphSpec extends ScalaCheckSuite:

  val rootId = NodeId("G")
  val a = NodeId("a")
  val b = NodeId("b")
  val c = NodeId("c")

  test("addEdge should add an edge between two nodes") {
    val graph =
      ViewerGraph(
        ViewerGraphData(
          arrows      = mutable.LinkedHashMap.empty,
          groups      = Map(rootId -> ViewerGroup(rootId)),
          nodes       = Map(a -> ViewerNode(a), b -> ViewerNode(b), c -> ViewerNode(c)),
          memberships = mutable.LinkedHashMap(rootId -> None, a -> Some(rootId), b -> Some(rootId), c -> Some(rootId))
        ),
        Some("G"),
        "digraph"
      )

    val edgeId = NodeId("a->b:1")
    val expected =
      ViewerGraph(
        ViewerGraphData(
          arrows      = mutable.LinkedHashMap(edgeId -> Arrow(a, b, seq = 1)),
          groups      = Map(rootId -> ViewerGroup(rootId)),
          nodes       = Map(a -> ViewerNode(a), b -> ViewerNode(b), c -> ViewerNode(c)),
          memberships = mutable.LinkedHashMap(rootId -> None, a -> Some(rootId), b -> Some(rootId), c -> Some(rootId), edgeId -> Some(rootId))
        ),
        Some("G"),
        "digraph"
      )

    val updated = graph.addEdge(a, b)

    assertEquals(updated, expected)
  }

  test("updateAttributes should update the attributes of an edge") {
    val edgeId = NodeId("a->b:0")
    val graph =
      ViewerGraph(
        ViewerGraphData(
          arrows      = mutable.LinkedHashMap(edgeId -> Arrow(a, b, Attributes(Map("id" -> AttrValue("1"))), 0)),
          groups      = Map(rootId -> ViewerGroup(rootId)),
          nodes       = Map(a -> ViewerNode(a), b -> ViewerNode(b)),
          memberships = mutable.LinkedHashMap(rootId -> None, a -> Some(rootId), b -> Some(rootId), edgeId -> Some(rootId))
        ),
        Some("G"),
        "digraph"
      )
    val expected =
      ViewerGraph(
        ViewerGraphData(
          arrows      = mutable.LinkedHashMap(edgeId -> Arrow(a, b, Attributes(Map("id" -> AttrValue("1"), "style" -> AttrValue("dashed"))), 0)),
          groups      = Map(rootId -> ViewerGroup(rootId)),
          nodes       = Map(a -> ViewerNode(a), b -> ViewerNode(b)),
          memberships = mutable.LinkedHashMap(rootId -> None, a -> Some(rootId), b -> Some(rootId), edgeId -> Some(rootId))
        ),
        Some("G"),
        "digraph"
      )

    val updated = graph.updateAttributes(Set(edgeId), Attributes(Map("style" -> AttrValue("dashed"))))
    pprint.log(updated)
    assertEquals(updated, expected)
  }

end ViewerGraphSpec
