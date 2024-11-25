package org.jpablo.graphexplorer.viewer.formats.dot.ast

import munit.ScalaCheckSuite
import org.jpablo.graphexplorer.viewer.models.Arrow.arrow
import org.jpablo.graphexplorer.viewer.models.ViewerNode.node
import org.jpablo.graphexplorer.viewer.models.{Arrow, Attributes, NodeId, ViewerGroup, ViewerNode}

class GraphElementOpsSpec extends ScalaCheckSuite:
  test("findAllElements should return all nodes") {
    val (_, _, nodes) = findAllDirectChildren(astWithNestedSubGraphs.asSubgraph)
    val expectedNodes =
      List(
        Some(NodeId("G"))         -> node("a"),
        Some(NodeId("G"))         -> node("b"),
        Some(NodeId("cluster_0")) -> node("z", Map("label" -> "ZZ")),
        Some(NodeId("cluster_1")) -> node("d")
      )
//    pprint.log(nodes)
    assertEquals(nodes, expectedNodes)
  }

  test("findAllElements should return all arrows") {
    val (arrows, _, _) = findAllDirectChildren(astWithNestedSubGraphs.asSubgraph)
//    pprint.log(arrows)
    val expectedArrows =
      List(
        Some(NodeId("G"))         -> arrow("x" -> "y"),
        Some(NodeId("cluster_0")) -> arrow("a" -> "b"),
        Some(NodeId("G"))         -> arrow("x" -> "a"),
        Some(NodeId("G"))         -> arrow("b" -> "c")
      )
    assertEquals(arrows, expectedArrows)
  }

  test("findAllElements should return all groups") {
    val (_, groups, _) = findAllDirectChildren(astWithNestedSubGraphs.asSubgraph)
//    pprint.log(groups)
    val expectedGroups =
      List(
        None                      -> ViewerGroup(NodeId("G")),
        Some(NodeId("G"))         -> ViewerGroup(NodeId("cluster_0"), nodeAttrs = Attributes(Map("shape" -> "egg"))),
        Some(NodeId("cluster_0")) -> ViewerGroup(NodeId("cluster_1"))
      )
    assertEquals(groups, expectedGroups)
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
