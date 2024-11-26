package org.jpablo.graphexplorer.viewer.formats.dot.ast

import munit.ScalaCheckSuite
import org.jpablo.graphexplorer.viewer.formats.dot.ast.viewerGraph.directChildrenToAST
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.Arrow.arrow
import org.jpablo.graphexplorer.viewer.models.ViewerNode.node
import org.jpablo.graphexplorer.viewer.models.{Arrow, Attributes, NodeId, ViewerGroup, ViewerNode}

class GraphElementOpsSpec extends ScalaCheckSuite:

  val root = ViewerGraph.defaultRootId
  val group0 = NodeId("cluster_0")
  val group1 = NodeId("cluster_1")

  test("findAllDirectChildren should return all nodes") {
    val data = findAllDirectChildren(astWithNestedSubGraphs.asSubgraph)
    val expectedNodes =
      List(
        node("a"),
        node("b"),
        node("z", Map("label" -> "ZZ")),
        node("d")
      )
//    pprint.log(data.memberships, showFieldNames = false)
    assertEquals(data.nodes, expectedNodes)
  }

  test("findAllDirectChildren should return all arrows") {
    val data = findAllDirectChildren(astWithNestedSubGraphs.asSubgraph)
    val expectedArrows =
      List(
        arrow("x" -> "y"),
        arrow("a" -> "b"),
        arrow("x" -> "a"),
        arrow("b" -> "c")
      )
    assertEquals(data.arrows, expectedArrows)
  }

  test("findAllDirectChildren should return all groups") {
    val data = findAllDirectChildren(astWithNestedSubGraphs.asSubgraph)
//    pprint.log(groups)
    val expectedGroups =
      List(
        ViewerGroup(root),
        ViewerGroup(group0, nodeAttrs = Attributes(Map("shape" -> "egg"))),
        ViewerGroup(group1)
      )
    assertEquals(data.groups, expectedGroups)
  }

  test("findAllDirectChildren in empty graphs should return all memberships") {
    val emptyAST = DotAST(tpe = "digraph", children = List(), id = Some(root.value))
    val data = findAllDirectChildren(emptyAST.asSubgraph)
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
    val data = findAllDirectChildren(astWithNestedSubGraphs.asSubgraph)
    val expectedMemberships =
      List(
        root             -> None,
        NodeId("a")      -> Some(root),
        NodeId("b")      -> Some(root),
        NodeId("x->y:0") -> Some(root),
        group0           -> Some(root),
        NodeId("z")      -> Some(group0),
        NodeId("a->b:0") -> Some(group0),
        group1           -> Some(group0),
        NodeId("d")      -> Some(group1),
        NodeId("x->a:0") -> Some(root),
        NodeId("b->c:0") -> Some(root)
      )

    assertEquals(data.memberships, expectedMemberships)
  }

  test("directChildrenToAST") {
    val data = findAllDirectChildren(astWithNestedSubGraphs.asSubgraph)
    val reconstructed = directChildrenToAST(data.toViewerGraphData)
    val expected =
      List(
        NodeStmt(DotNodeId("a", None), List()),
        NodeStmt(DotNodeId("b", None), List()),
        EdgeStmt(List(DotNodeId("x", None), DotNodeId("y", None)), List()),
        EdgeStmt(List(DotNodeId("x", None), DotNodeId("a", None)), List()),
        EdgeStmt(List(DotNodeId("b", None), DotNodeId("c", None)), List()),
        SubGraph(
          List(
            AttrStmt("node", List(Attr("shape", "egg"))),
            NodeStmt(DotNodeId("z", None), List(Attr("label", "ZZ"))),
            EdgeStmt(List(DotNodeId("a", None), DotNodeId("b", None)), List()),
            SubGraph(List(NodeStmt(DotNodeId("d", None), List())), Some("cluster_1"))
          ),
          Some("cluster_0")
        )
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
          AttrStmt("node", List(Attr("shape", "egg"))),
          StmtSep(),
          Newline(),
          Pad(),
          NodeStmt(DotNodeId("z", None), List(Attr("label", "ZZ"))),
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
