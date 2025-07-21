package org.jpablo.graphexplorer.viewer.graph

import munit.ScalaCheckSuite
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.SimpleGraphConverter
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Style
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.ViewerGroup.group
import org.jpablo.graphexplorer.viewer.models.ViewerNode.nodeWithId

import scala.collection.immutable.VectorMap

class ViewerGraphSpec extends ScalaCheckSuite:

  val rootId = ViewerGraphElements.defaultRootId
  val rootGroup = group(rootId)

  val a = NodeId("a")
  val b = NodeId("b")
  val c = NodeId("c")

  test("addArrow should add an arrow between two nodes") {
    val arrow = Arrow(a, b)
    val graph = ViewerGraph(ViewerGraphElements(nodes = VectorMap(nodeWithId(a), nodeWithId(b), nodeWithId(c))))

    val expected =
      ViewerGraph(
        ViewerGraphElements(
          nodes  = VectorMap(nodeWithId(a), nodeWithId(b), nodeWithId(c)),
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
        ViewerGraphElements
          (
          nodes  = VectorMap(nodeWithId(a), nodeWithId(b)),
          arrows = Map(arrow.id -> arrow)
        )
      )
    val expected =
      ViewerGraph(
        ViewerGraphElements(
          nodes  = VectorMap(nodeWithId(a), nodeWithId(b)),
          arrows = Map(arrow.id -> Arrow(a, b, Attributes.of(Style -> Style.dashed)))
        )
      )

    val updated =
      graph.updateAttributes(
        ElementIds.from(arrow.id),
        AttributeUpdates.of(Style -> Style.dashed)
      )

    assertEquals(updated, expected)
  }

  test("removeNodes should remove the nodes and their edges") {
    val arrowId1 = ArrowId("a->b:0")
    val arrowId2 = ArrowId("b->c:0")
    val graph =
      ViewerGraph(
        ViewerGraphElements(
          nodes = VectorMap(nodeWithId(a), nodeWithId(b), nodeWithId(c)),
          arrows = Map(
            arrowId1 -> Arrow(a, b),
            arrowId2 -> Arrow(b, c)
          )
        )
      )

    val expected =
      ViewerGraph(ViewerGraphElements(nodes = VectorMap(nodeWithId(a), nodeWithId(c))))

    val updated = graph.removeElements(ElementIds.from(b))
    assertEquals(updated, expected)
  }

  test("removeNodes a single arrow") {
    val arrowId1 = ArrowId("a->b:0")
    val arrowId2 = ArrowId("a->b:1")
    val graph =
      ViewerGraph(
        ViewerGraphElements(
          nodes = VectorMap(nodeWithId(a), nodeWithId(b)),
          arrows = Map(
            arrowId1 -> Arrow(a, b),
            arrowId2 -> Arrow(a, b, seq = 1)
          )
        )
      )

    val expected =
      ViewerGraph(
        ViewerGraphElements(
          nodes  = VectorMap(nodeWithId(a), nodeWithId(b)),
          arrows = Map(arrowId2 -> Arrow(a, b, seq = 1))
        )
      )

    val updated = graph.removeElements(ElementIds.from(arrowId1))

    assertEquals(updated, expected)
  }

  test("removeElements should clean up memberships when removing nodes from groups") {
    // Reproduce the scenario from the DOT state:
    // digraph "G" {
    //   subgraph "gdd30b2d0" {
    //     "a" [label="a"];
    //     "b" [label=""];
    //   }
    //   "a" -> "b";
    // }
    
    val groupId = GroupId("gdd30b2d0")
    val aNode = NodeId("a")
    val bNode = NodeId("b")
    
    val elements = ViewerGraphElements(
      nodes = VectorMap(
        nodeWithId(aNode, "label" -> "a"),
        nodeWithId(bNode, "label" -> "")
      ),
      arrows = Map(
        ArrowId("a->b:0") -> Arrow(aNode, bNode)
      ),
      groups = Map(
        groupId -> group(groupId, Attributes.of(
          "label" -> "A title",
          "lheight" -> "0.23",
          "lp" -> "43,169.2",
          "lwidth" -> "0.49",
          "cluster" -> "true"
        ))
      ),
      memberships = Map(
        aNode -> groupId,
        bNode -> groupId
      ),
      graphAttributes = Attributes.of("label" -> "A title")
    )
    
    val graph = ViewerGraph(elements)
    
    // Try to remove node "b" - this should not cause NoSuchElementException
    val updatedGraph = graph.removeElements(ElementIds.from(bNode))
    
    // Verify the node was removed
    assert(!updatedGraph.nodes.contains(bNode))
    
    // Verify the arrow was also removed (since it referenced the removed node)
    assertEquals(updatedGraph.arrows.size, 0)
    
    // Verify membership was cleaned up
    assert(!updatedGraph.memberships.contains(bNode))
    
    // This should not throw NoSuchElementException when converting to SimpleGraph
    val simpleGraph = SimpleGraphConverter.fromViewerGraphElements(updatedGraph.elements)
    assert(simpleGraph != null)
  }

end ViewerGraphSpec
