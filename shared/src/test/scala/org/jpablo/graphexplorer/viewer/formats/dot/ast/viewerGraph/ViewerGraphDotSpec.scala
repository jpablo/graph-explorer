package org.jpablo.graphexplorer.viewer.formats.dot.ast.viewerGraph

import munit.ScalaCheckSuite
import org.jpablo.graphexplorer.viewer.formats.dot.ast.*
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.GraphType.digraph
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Label
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, ViewerGraphElements}
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.Arrow.arrow
import org.jpablo.graphexplorer.viewer.models.ViewerGroup.group

class ViewerGraphDotSpec extends ScalaCheckSuite:
  val rootId = ViewerGraphElements.defaultRootId
  val initialGroup = group(rootId)

  val a = NodeId("a")
  val b = NodeId("b")
  val c = NodeId("c")

  test("graphToDotAST should convert a ViewerGraph to a DotAST: two nodes and an arrow") {
    val graph =
      ViewerGraph(ViewerGraphElements(nodes = Map(nodeWithId(a), nodeWithId(b)), arrows = Map(arrow(a, b))))

    val ast = graphToDotAST(graph)

    val expected =
      DotAST(
        digraph.toString,
        List(
          NodeStmt(DotNodeId("a")),
          NodeStmt(DotNodeId("b")),
          EdgeStmt(List(DotNodeId("a"), DotNodeId("b")), List(Attr("id", AttrValue("1"))))
        ),
        Some("G")
      )

    assertEquals(ast, expected)
  }

  test("graphToDotAST should convert a ViewerGraph to a DotAST: two nodes and an arrow with one group") {

    val ab = arrow(a, b)
    // Setup initial graph with nodes
    val graph = ViewerGraph(ViewerGraphElements(nodes = Map(nodeWithId(a), nodeWithId(b)), arrows = Map(ab)))

    // Add elements to a new group with a label
    val updatedGraph = graph.moveToNewGroup("New Group", a)
    val newGroupId = updatedGraph.membership(a).get

    val expected =
      ViewerGraph(
        ViewerGraphElements(
          nodes       = Map(nodeWithId(a), nodeWithId(b)),
          arrows      = Map(ab),
          memberships = Map(a -> newGroupId),
          groups = Map(
            initialGroup,
            newGroupId -> ViewerGroup(newGroupId, Attributes.of(Label -> "New Group"))
          )
        )
      )

    // sanity check
    assertEquals(updatedGraph, expected)

    val ast = graphToDotAST(updatedGraph)

    val expectedAST =
      DotAST(
        digraph.toString,
        List(
          SubGraph(
            List(
              AttrStmt("graph", List(Attr("label", AttrValue("New Group")))),
              NodeStmt(DotNodeId("a"))
            ),
            Some(newGroupId.value)
          ),
          NodeStmt(DotNodeId("b")),
          EdgeStmt(List(DotNodeId("a"), DotNodeId("b")), List(Attr("id", AttrValue("1"))))
        ),
        Some("G")
      )

    assertEquals(ast, expectedAST)
  }
