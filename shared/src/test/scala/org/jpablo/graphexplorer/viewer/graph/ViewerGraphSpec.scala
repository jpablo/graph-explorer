package org.jpablo.graphexplorer.viewer.graph

import munit.ScalaCheckSuite
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.AttrStatus.Single

class ViewerGraphSpec extends ScalaCheckSuite:

  val rootId = ViewerGraphData.defaultRootId
  val rootGroup = ViewerGroup(rootId)

  val a = NodeId("a")
  val b = NodeId("b")
  val c = NodeId("c")

  test("addEdge should add an arrow between two nodes") {
    val graph =
      ViewerGraph(
        ViewerGraphData(nodes = Map(a -> ViewerNode(a), b -> ViewerNode(b), c -> ViewerNode(c)))
      )

    val arrowId = ArrowId("a->b:1")
    val expected =
      ViewerGraph(
        ViewerGraphData(
          arrows = Map(arrowId -> Arrow(a, b, seq = 1)),
          nodes  = Map(a -> ViewerNode(a), b -> ViewerNode(b), c -> ViewerNode(c))
        )
      )

    val updated = graph.addEdge(a, b)._1

    assertEquals(updated, expected)
  }

  test("updateAttributes should update the attributes of an arrow") {
    val arrowId = ArrowId("a->b:0")
    val graph =
      ViewerGraph(
        ViewerGraphData(
          nodes  = Map(a -> ViewerNode(a), b -> ViewerNode(b)),
          arrows = Map(arrowId -> Arrow(a, b, Attributes(Map(AttributeId("id") -> AttrValue("1"))), 0))
        )
      )
    val expected =
      ViewerGraph(
        ViewerGraphData(
          nodes = Map(a -> ViewerNode(a), b -> ViewerNode(b)),
          arrows =
            Map(arrowId ->
              Arrow(
                a,
                b,
                Attributes(Map(AttributeId("id") -> AttrValue("1"), AttributeId("style") -> AttrValue("dashed"))),
                0
              ))
        ),
        "digraph"
      )

    val updated = graph.updateAttributes(
      ElementIds.from(arrowId),
      AttributesUpdates(Map(AttributeId("style") -> Single(AttrValue("dashed"))))
    )
    pprint.log(updated)
    assertEquals(updated, expected)
  }

  test("removeNodes should remove the nodes and their edges") {
    val arrowId1 = ArrowId("a->b:0")
    val arrowId2 = ArrowId("b->c:0")
    val graph =
      ViewerGraph(
        ViewerGraphData(
          nodes = Map(
            a -> ViewerNode(a),
            b -> ViewerNode(b),
            c -> ViewerNode(c)
          ),
          arrows = Map(
            arrowId1 -> Arrow(a, b, seq = 0),
            arrowId2 -> Arrow(b, c, seq = 0)
          )
        )
      )

    val expected =
      ViewerGraph(
        ViewerGraphData(
          nodes = Map(
            a -> ViewerNode(a),
            c -> ViewerNode(c)
          )
        )
      )

    val updated = graph.removeElements(ElementIds.from(b))
    assertEquals(updated, expected)
  }

  test("removeNodes a single arrow") {
    val arrowId1 = ArrowId("a->b:0")
    val arrowId2 = ArrowId("a->b:1")
    val graph =
      ViewerGraph(
        ViewerGraphData(
          nodes = Map(
            a -> ViewerNode(a),
            b -> ViewerNode(b)
          ),
          arrows = Map(
            arrowId1 -> Arrow(a, b, seq = 0),
            arrowId2 -> Arrow(a, b, seq = 1)
          )
        )
      )

    val expected =
      ViewerGraph(
        ViewerGraphData(
          nodes = Map(
            a -> ViewerNode(a),
            b -> ViewerNode(b)
          ),
          arrows = Map(
            arrowId2 -> Arrow(a, b, seq = 1)
          )
        )
      )

    val updated = graph.removeElements(ElementIds.from(arrowId1))

    assertEquals(updated, expected)
  }

end ViewerGraphSpec
