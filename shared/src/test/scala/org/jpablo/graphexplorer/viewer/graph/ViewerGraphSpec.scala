package org.jpablo.graphexplorer.viewer.graph

import munit.ScalaCheckSuite
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.models.*

class ViewerGraphSpec extends ScalaCheckSuite:

  val rootId = ElementId("G")
  val a = ElementId("a")
  val b = ElementId("b")
  val c = ElementId("c")

  test("addEdge should add an edge between two nodes") {
    val graph =
      ViewerGraph(
        "G",
        ViewerGraphData(
          arrows      = Map.empty,
          groups      = Map(rootId -> ViewerGroup(rootId)),
          nodes       = Map(a -> ViewerNode(a), b -> ViewerNode(b), c -> ViewerNode(c)),
          memberships = Map(rootId -> None, a -> Some(rootId), b -> Some(rootId), c -> Some(rootId))
        ),
        "digraph"
      )

    val edgeId = ElementId("a->b:1")
    val expected =
      ViewerGraph(
        "G",
        ViewerGraphData(
          arrows      = Map(edgeId -> Arrow(a, b, seq = 1)),
          groups      = Map(rootId -> ViewerGroup(rootId)),
          nodes       = Map(a -> ViewerNode(a), b -> ViewerNode(b), c -> ViewerNode(c)),
          memberships = Map(rootId -> None, a -> Some(rootId), b -> Some(rootId), c -> Some(rootId), edgeId -> Some(rootId))
        ),
        "digraph"
      )

    val updated = graph.addEdge(a, b)

    assertEquals(updated, expected)
  }

  test("updateAttributes should update the attributes of an edge") {
    val edgeId = ElementId("a->b:0")
    val graph =
      ViewerGraph(
        "G",
        ViewerGraphData(
          arrows      = Map(edgeId -> Arrow(a, b, Attributes(Map("id" -> AttrValue("1"))), 0)),
          groups      = Map(rootId -> ViewerGroup(rootId)),
          nodes       = Map(a -> ViewerNode(a), b -> ViewerNode(b)),
          memberships = Map(rootId -> None, a -> Some(rootId), b -> Some(rootId), edgeId -> Some(rootId))
        ),
        "digraph"
      )
    val expected =
      ViewerGraph(
        "G",
        ViewerGraphData(
          arrows      = Map(edgeId -> Arrow(a, b, Attributes(Map("id" -> AttrValue("1"), "style" -> AttrValue("dashed"))), 0)),
          groups      = Map(rootId -> ViewerGroup(rootId)),
          nodes       = Map(a -> ViewerNode(a), b -> ViewerNode(b)),
          memberships = Map(rootId -> None, a -> Some(rootId), b -> Some(rootId), edgeId -> Some(rootId))
        ),
        "digraph"
      )

    val updated = graph.updateAttributes(Set(edgeId), Attributes(Map("style" -> AttrValue("dashed"))))
    pprint.log(updated)
    assertEquals(updated, expected)
  }

end ViewerGraphSpec
