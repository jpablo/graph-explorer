package org.jpablo.graphexplorer.viewer.formats.dot.ast.viewerGraph

import munit.ScalaCheckSuite
import org.jpablo.graphexplorer.viewer.formats.dot.ast.*
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.GraphType.digraph
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Label
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, ViewerGraphElements}
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.Arrow.arrow
import org.jpablo.graphexplorer.viewer.models.ViewerGroup.{defaultGroupAttributes, group, groupWithId}
import org.jpablo.graphexplorer.viewer.models.ViewerNode.{defaultNodeAttributes, nodeWithId}

import scala.collection.immutable.VectorMap

class ViewerGraphDotSpec extends ScalaCheckSuite:
  val rootId       = ViewerGraphElements.defaultRootId
  val initialGroup = groupWithId(rootId)

  val a = NodeId("a")
  val b = NodeId("b")
  val c = NodeId("c")

  val defaultNodeDotAttrs  = defaultNodeAttributes.toDotAttr
  val defaultGroupAttrStmt = AttrStmt("graph", defaultGroupAttributes.toDotAttr)

  test("graphToDotAST should convert a ViewerGraph to a DotAST: two nodes and an arrow") {
    val graph =
      ViewerGraph(ViewerGraphElements(nodes = VectorMap(nodeWithId(a), nodeWithId(b)), arrows = Map(arrow(a, b))))

    val ast = graphToDotAST(graph)

    val expected =
      DotAST(
        digraph.toString,
        List(
          NodeStmt(DotNodeId("a"), defaultNodeDotAttrs),
          NodeStmt(DotNodeId("b"), defaultNodeDotAttrs),
          EdgeStmt(List(DotNodeId("a"), DotNodeId("b")), List(Attr("id", AttrValue("1"))))
        ),
        Some("G")
      )

    assertEquals(ast, expected)
  }

  test("graphToDotAST should convert a ViewerGraph to a DotAST: two nodes and an arrow with one group") {

    val ab = arrow(a, b)
    // Setup initial graph with nodes
    val graph = ViewerGraph(ViewerGraphElements(nodes = VectorMap(nodeWithId(a), nodeWithId(b)), arrows = Map(ab)))

    // Add elements to a new group with a label
    val updatedGraph = graph.moveToNewGroup("New Group", a)
    val newGroupId   = updatedGraph.membership(a).get

    val expected =
      ViewerGraph(
        ViewerGraphElements(
          nodes = VectorMap(nodeWithId(a), nodeWithId(b)),
          arrows = Map(ab),
          memberships = VectorMap(a -> newGroupId),
          groups = Map(newGroupId -> group(newGroupId, Attributes.of(Label -> "New Group")))
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
              AttrStmt("graph", (defaultGroupAttributes + (Label.attrId -> AttrValue("New Group"))).toDotAttr),
              NodeStmt(DotNodeId("a"), defaultNodeDotAttrs)
            ),
            Some(newGroupId.value)
          ),
          NodeStmt(DotNodeId("b"), defaultNodeDotAttrs),
          EdgeStmt(List(DotNodeId("a"), DotNodeId("b")), List(Attr("id", AttrValue("1"))))
        ),
        Some("G")
      )

    assertEquals(ast, expectedAST)
  }
