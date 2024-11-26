package org.jpablo.graphexplorer.viewer.formats.dot.ast

import munit.ScalaCheckSuite
import org.jpablo.graphexplorer.viewer.formats.dot.ast.viewerGraph.directChildrenToAST
import org.jpablo.graphexplorer.viewer.models.Arrow.arrow
import org.jpablo.graphexplorer.viewer.models.ViewerNode.node
import org.jpablo.graphexplorer.viewer.models.{Arrow, Attributes, NodeId, ViewerGroup, ViewerNode}

class GraphElementOpsSpec extends ScalaCheckSuite:

  val root = NodeId("G")
  val group0 = NodeId("cluster_0")
  val grup1 = NodeId("cluster_1")

  test("findAllElements should return all nodes") {
    val data = findAllDirectChildren(astWithNestedSubGraphs.asSubgraph)
    val expectedNodes =
      List(
        Some(root)   -> node("a"),
        Some(root)   -> node("b"),
        Some(group0) -> node("z", Map("label" -> "ZZ")),
        Some(grup1)  -> node("d")
      )
//    pprint.log(nodes)
    assertEquals(data.nodes, expectedNodes)
  }

  test("findAllElements should return all arrows") {
    val data = findAllDirectChildren(astWithNestedSubGraphs.asSubgraph)
//    pprint.log(arrows)
    val expectedArrows =
      List(
        Some(root)   -> arrow("x" -> "y"),
        Some(group0) -> arrow("a" -> "b"),
        Some(root)   -> arrow("x" -> "a"),
        Some(root)   -> arrow("b" -> "c")
      )
    assertEquals(data.arrows, expectedArrows)
  }

  test("findAllElements should return all groups") {
    val data = findAllDirectChildren(astWithNestedSubGraphs.asSubgraph)
//    pprint.log(groups)
    val expectedGroups =
      List(
        None         -> ViewerGroup(root),
        Some(root)   -> ViewerGroup(group0, nodeAttrs = Attributes(Map("shape" -> "egg"))),
        Some(group0) -> ViewerGroup(grup1)
      )
    assertEquals(data.groups, expectedGroups)
  }

  test("directChildrenToAST") {
    val data = findAllDirectChildren(astWithNestedSubGraphs.asSubgraph)
    val reconstructed = directChildrenToAST(data)
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
