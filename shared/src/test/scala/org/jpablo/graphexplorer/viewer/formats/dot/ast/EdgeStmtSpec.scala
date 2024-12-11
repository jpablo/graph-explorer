package org.jpablo.graphexplorer.viewer.formats.dot.ast

import munit.ScalaCheckSuite
import org.jpablo.graphexplorer.viewer.models.*

class EdgeStmtSpec extends ScalaCheckSuite {
  test("expandArrows should process simple nodes (case 2)") {
    val edgeStmt =
      EdgeStmt(
        List(DotNodeId("a", None), DotNodeId("b", None), DotNodeId("c", None), DotNodeId("d", None)),
        Nil
      )
    EdgeStmt.resetId()
    val expanded = edgeStmt.expandArrows
    val expected =
      List(
        List(Arrow(NodeId("a"), NodeId("b"), Attributes(Map()), 1)),
        List(Arrow(NodeId("b"), NodeId("c"), Attributes(Map()), 2)),
        List(Arrow(NodeId("c"), NodeId("d"), Attributes(Map()), 3))
      )
    assertEquals(expanded, expected)
  }

  test("expandArrows should process a node and a group (case 3)") {
    val edgeStmt =
      EdgeStmt(
        List(
          DotNodeId("a", None),
          SubGraph(List(NodeStmt(DotNodeId("b", None), Nil), NodeStmt(DotNodeId("c", None), Nil)), None)
        ),
        List(Attr("id", AttrValue("1")))
      )
    EdgeStmt.resetId()
    val expanded = edgeStmt.expandArrows
    val expected =
      List(
        List(
          Arrow(NodeId("a"), NodeId("b"), Attributes(Map("id" -> AttrValue("1"))), 1),
          Arrow(NodeId("a"), NodeId("c"), Attributes(Map("id" -> AttrValue("1"))), 2)
        )
      )
    assertEquals(expanded, expected)
  }

  test("expandArrows should process a group and a node (case 4)") {
    val edgeStmt =
      EdgeStmt(
        List(
          SubGraph(List(NodeStmt(DotNodeId("a", None), Nil), NodeStmt(DotNodeId("b", None), Nil)), None),
          DotNodeId("c", None)
        ),
        List(Attr("id", AttrValue("1")))
      )
    EdgeStmt.resetId()
    val expanded = edgeStmt.expandArrows
    val expected =
      List(
        List(
          Arrow(NodeId("a"), NodeId("c"), Attributes(Map("id" -> AttrValue("1"))), 1),
          Arrow(NodeId("b"), NodeId("c"), Attributes(Map("id" -> AttrValue("1"))), 2)
        )
      )
    assertEquals(expanded, expected)
  }

  test("expandArrows should process a group and a group (case 5)") {
    val edgeStmt =
      EdgeStmt(
        List(
          SubGraph(List(NodeStmt(DotNodeId("a", None), Nil), NodeStmt(DotNodeId("b", None), Nil)), None),
          SubGraph(List(NodeStmt(DotNodeId("c", None), Nil), NodeStmt(DotNodeId("d", None), Nil)), None)
        ),
        List(Attr("id", AttrValue("1")))
      )
    EdgeStmt.resetId()
    val expanded = edgeStmt.expandArrows
    val expected =
      List(
        List(
          Arrow(NodeId("a"), NodeId("c"), Attributes(Map("id" -> AttrValue("1"))), 1),
          Arrow(NodeId("a"), NodeId("d"), Attributes(Map("id" -> AttrValue("1"))), 2),
          Arrow(NodeId("b"), NodeId("c"), Attributes(Map("id" -> AttrValue("1"))), 3),
          Arrow(NodeId("b"), NodeId("d"), Attributes(Map("id" -> AttrValue("1"))), 4)
        )
      )
    assertEquals(expanded, expected)
  }
}
