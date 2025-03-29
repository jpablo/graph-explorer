package org.jpablo.graphexplorer.viewer.graph

import munit.ScalaCheckSuite
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Style
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.ViewerGroup.group
import org.jpablo.graphexplorer.viewer.models.ViewerNode.nodeWithId

class ViewerGraphSpec extends ScalaCheckSuite:

  val rootId = ViewerGraphElements.defaultRootId
  val rootGroup = group(rootId)

  val a = NodeId("a")
  val b = NodeId("b")
  val c = NodeId("c")

  test("addArrow should add an arrow between two nodes") {
    val arrow = Arrow(a, b)
    val graph = ViewerGraph(ViewerGraphElements(nodes = Map(nodeWithId(a), nodeWithId(b), nodeWithId(c))))

    val expected =
      ViewerGraph(
        ViewerGraphElements(
          nodes  = Map(nodeWithId(a), nodeWithId(b), nodeWithId(c)),
          arrows = Map(arrow.id -> Arrow(a, b))
        )
      )

    val updated = graph.addArrow(a, b)._1

    assertEquals(updated, expected)
  }

  test("updateAttributes should update the attributes of an arrow") {
    val arrow = Arrow(a, b)
    val graph =
      ViewerGraph(
        ViewerGraphElements(
          nodes  = Map(nodeWithId(a), nodeWithId(b)),
          arrows = Map(arrow.id -> arrow)
        )
      )
    val expected =
      ViewerGraph(
        ViewerGraphElements(
          nodes  = Map(nodeWithId(a), nodeWithId(b)),
          arrows = Map(arrow.id -> Arrow(a, b, Attributes.of(Style -> Style.dashed)))
        )
      )

    val updated =
      graph.updateAttributes(
        ElementIds.from(arrow.id),
        AttributesUpdates(update = Attributes.of(Style -> Style.dashed).values)
      )

    assertEquals(updated, expected)
  }

  test("removeNodes should remove the nodes and their edges") {
    val arrowId1 = ArrowId("a->b:0")
    val arrowId2 = ArrowId("b->c:0")
    val graph =
      ViewerGraph(
        ViewerGraphElements(
          nodes = Map(nodeWithId(a), nodeWithId(b), nodeWithId(c)),
          arrows = Map(
            arrowId1 -> Arrow(a, b),
            arrowId2 -> Arrow(b, c)
          )
        )
      )

    val expected =
      ViewerGraph(ViewerGraphElements(nodes = Map(nodeWithId(a), nodeWithId(c))))

    val updated = graph.removeElements(ElementIds.from(b))
    assertEquals(updated, expected)
  }

  test("removeNodes a single arrow") {
    val arrowId1 = ArrowId("a->b:0")
    val arrowId2 = ArrowId("a->b:1")
    val graph =
      ViewerGraph(
        ViewerGraphElements(
          nodes = Map(nodeWithId(a), nodeWithId(b)),
          arrows = Map(
            arrowId1 -> Arrow(a, b),
            arrowId2 -> Arrow(a, b, seq = 1)
          )
        )
      )

    val expected =
      ViewerGraph(
        ViewerGraphElements(
          nodes  = Map(nodeWithId(a), nodeWithId(b)),
          arrows = Map(arrowId2 -> Arrow(a, b, seq = 1))
        )
      )

    val updated = graph.removeElements(ElementIds.from(arrowId1))

    assertEquals(updated, expected)
  }

end ViewerGraphSpec
