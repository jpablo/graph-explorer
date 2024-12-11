package org.jpablo.graphexplorer.viewer.formats.dot.ast

import munit.ScalaCheckSuite
import org.jpablo.graphexplorer.viewer.formats.dot.ast.EdgeStmt.resetId
import org.jpablo.graphexplorer.viewer.formats.dot.ast.viewerGraph.graphDataToAST
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.Arrow.arrow
import org.jpablo.graphexplorer.viewer.models.ViewerNode.node
import org.jpablo.graphexplorer.viewer.models.{Arrow, Attributes, NodeId, ViewerGroup, ViewerNode}

class GraphElementOpsSpec extends ScalaCheckSuite:

  val root = ViewerGraph.defaultRootId
  val group0 = NodeId("cluster_0")
  val group1 = NodeId("cluster_1")

  test("findAllDirectChildren should return all nodes") {
    val data = toFlattenedElements(astWithNestedSubGraphs.asSubgraph)
    val expectedNodes =
      List(
        node("a"),
        node("b"),
        node("z", Map("label" -> AttrValue("ZZ"))),
        node("d")
      )
    assertEquals(data.nodes, expectedNodes)
  }

  test("findAllDirectChildren should return all arrows") {
    resetId()
    val data = astWithNestedSubGraphs.asSubgraph.toFlattenedElements
    val expectedArrows =
      List(
        arrow("x" -> "y", seq = 1),
        arrow("a" -> "b", seq = 2),
        arrow("x" -> "a", seq = 3),
        arrow("b" -> "c", seq = 4)
      )
    assertEquals(data.arrows, expectedArrows)
  }

  test("findAllDirectChildren should return all groups") {
    val data = toFlattenedElements(astWithNestedSubGraphs.asSubgraph)
    val expectedGroups =
      List(
        ViewerGroup(root),
        ViewerGroup(group0, nodeAttrs = Attributes(Map("shape" -> AttrValue("egg")))),
        ViewerGroup(group1)
      )
    assertEquals(data.groups, expectedGroups)
  }

  test("findAllDirectChildren in empty graphs should return all memberships") {
    val emptyAST = DotAST(tpe = "digraph", children = List(), id = Some(root.value))
    val data = toFlattenedElements(emptyAST.asSubgraph)
    val expected =
      FlattenedGraphElement(
        arrows      = List(),
        groups      = List(ViewerGroup(root)),
        nodes       = List(),
        memberships = List((root, None))
      )

    assertEquals(data, expected)
  }

  test("findAllDirectChildren should return all memberships") {
    resetId()
    val data = astWithNestedSubGraphs.asSubgraph.toFlattenedElements
    val expectedMemberships =
      List(
        NodeId("b->c:4") -> Some(root),
        NodeId("x->a:3") -> Some(root),
        NodeId("d")      -> Some(group1),
        group1           -> Some(group0),
        NodeId("a->b:2") -> Some(group0),
        NodeId("z")      -> Some(group0),
        group0           -> Some(root),
        NodeId("x->y:1") -> Some(root),
        NodeId("b")      -> Some(root),
        NodeId("a")      -> Some(root),
        root             -> None
      )

    assertEquals(data.memberships, expectedMemberships)
  }

  test("directChildrenToAST") {
    resetId()
    val flattened = astWithNestedSubGraphs.asSubgraph.toFlattenedElements
    val data = flattened.toViewerGraphData
    val reconstructed = graphDataToAST(data)
    val expected =
      List(
        SubGraph(
          List(
            AttrStmt("node", List(Attr("shape", AttrValue("egg")))),
            SubGraph(List(NodeStmt(DotNodeId("d", None), List())), Some("cluster_1")),
            NodeStmt(DotNodeId("z", None), List(Attr("label", AttrValue("ZZ")))),
            EdgeStmt(List(DotNodeId("a", None), DotNodeId("b", None)), List(Attr("id", AttrValue("2"))))
          ),
          Some("cluster_0")
        ),
        NodeStmt(DotNodeId("a", None), List()),
        NodeStmt(DotNodeId("b", None), List()),
        EdgeStmt(List(DotNodeId("x", None), DotNodeId("y", None)), List(Attr("id", AttrValue("1")))),
        EdgeStmt(List(DotNodeId("x", None), DotNodeId("a", None)), List(Attr("id", AttrValue("3")))),
        EdgeStmt(List(DotNodeId("b", None), DotNodeId("c", None)), List(Attr("id", AttrValue("4"))))
      )
    assertEquals(reconstructed, expected)
  }

// viewer/testOnly org.jpablo.graphexplorer.viewer.formats.dot.ast.DotASTParsingTest
val astWithNestedSubGraphs =
  DotAST(
    "digraph",
    List(
      Newline(),
      Pad(),
      NodeStmt(DotNodeId("a", None), List()),
      Newline(),
      Pad(),
      NodeStmt(DotNodeId("b", None), List()),
      Newline(),
      Pad(),
      EdgeStmt(List(DotNodeId("x", None), DotNodeId("y", None)), List()),
      Newline(),
      Pad(),
      SubGraph(
        List(
          Newline(),
          Pad(),
          AttrStmt("node", List(Attr("shape", AttrValue("egg")))),
          StmtSep(),
          Newline(),
          Pad(),
          NodeStmt(DotNodeId("z", None), List(Attr("label", AttrValue("ZZ")))),
          Newline(),
          Pad(),
          EdgeStmt(List(DotNodeId("a", None), DotNodeId("b", None)), List()),
          Newline(),
          Pad(),
          SubGraph(
            List(Newline(), Pad(), NodeStmt(DotNodeId("d", None), List()), Newline(), Pad()),
            Some("cluster_1")
          ),
          Newline(),
          Pad()
        ),
        Some("cluster_0")
      ),
      Newline(),
      Pad(),
      EdgeStmt(List(DotNodeId("x", None), DotNodeId("a", None)), List()),
      Newline(),
      Pad(),
      EdgeStmt(List(DotNodeId("b", None), DotNodeId("c", None)), List()),
      Newline()
    ),
    Some("G")
  )
