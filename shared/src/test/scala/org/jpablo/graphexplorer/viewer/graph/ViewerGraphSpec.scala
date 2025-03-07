package org.jpablo.graphexplorer.viewer.graph

import munit.ScalaCheckSuite
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.ViewerNode.node

class ViewerGraphSpec extends ScalaCheckSuite:

  val rootId = ViewerGraphData.defaultRootId
  val rootGroup = ViewerGroup(rootId)

  val a = NodeId("a")
  val b = NodeId("b")
  val c = NodeId("c")

  test("addEdge should add an arrow between two nodes") {
    val arrow = Arrow(a, b)
    val graph = ViewerGraph(ViewerGraphData(nodes = Map(node(a), node(b), node(c))))

    val expected =
      ViewerGraph(
        ViewerGraphData(
          nodes  = Map(node(a), node(b), node(c)),
          arrows = Map(arrow.id -> Arrow(a, b))
        )
      )

    val updated = graph.addEdge(a, b)._1

    assertEquals(updated, expected)
  }

  test("updateAttributes should update the attributes of an arrow") {
    val arrow = Arrow(a, b)
    val graph =
      ViewerGraph(
        ViewerGraphData(
          nodes  = Map(node(a), node(b)),
          arrows = Map(arrow.id -> arrow)
        )
      )
    val expected =
      ViewerGraph(
        ViewerGraphData(
          nodes  = Map(node(a), node(b)),
          arrows = Map(arrow.id -> Arrow(a, b, Attributes(Map(AttributeId("style") -> AttrValue("dashed")))))
        )
      )

    val updated =
      graph.updateAttributes(
        ElementIds.from(arrow.id),
        AttributesUpdates(update = Map(AttributeId("style") -> AttrValue("dashed")))
      )

    assertEquals(updated, expected)
  }

  test("removeNodes should remove the nodes and their edges") {
    val arrowId1 = ArrowId("a->b:0")
    val arrowId2 = ArrowId("b->c:0")
    val graph =
      ViewerGraph(
        ViewerGraphData(
          nodes = Map(node(a), node(b), node(c)),
          arrows = Map(
            arrowId1 -> Arrow(a, b),
            arrowId2 -> Arrow(b, c)
          )
        )
      )

    val expected =
      ViewerGraph(ViewerGraphData(nodes = Map(node(a), node(c))))

    val updated = graph.removeElements(ElementIds.from(b))
    assertEquals(updated, expected)
  }

  test("removeNodes a single arrow") {
    val arrowId1 = ArrowId("a->b:0")
    val arrowId2 = ArrowId("a->b:1")
    val graph =
      ViewerGraph(
        ViewerGraphData(
          nodes = Map(node(a), node(b)),
          arrows = Map(
            arrowId1 -> Arrow(a, b),
            arrowId2 -> Arrow(a, b, seq = 1)
          )
        )
      )

    val expected =
      ViewerGraph(
        ViewerGraphData(
          nodes  = Map(node(a), node(b)),
          arrows = Map(arrowId2 -> Arrow(a, b, seq = 1))
        )
      )

    val updated = graph.removeElements(ElementIds.from(arrowId1))

    assertEquals(updated, expected)
  }

end ViewerGraphSpec
