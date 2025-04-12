package org.jpablo.graphexplorer.viewer.formats.dot.ast

import munit.ScalaCheckSuite
import org.jpablo.graphexplorer.viewer.formats.dot.ast.DotASTOps.edgeToViewerArrows
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Id
import org.jpablo.graphexplorer.viewer.models.*

class EdgeStmtSpec extends ScalaCheckSuite {
  val idAttr = Id.attrId

  val a: NodeId = NodeId("a")
  val b: NodeId = NodeId("b")
  val c: NodeId = NodeId("c")
  val d: NodeId = NodeId("d")

  val dotA = DotNodeId("a")
  val dotB = DotNodeId("b")
  val dotC = DotNodeId("c")
  val dotD = DotNodeId("d")

  test("expandArrows should process simple nodes (case 2)") {
    val edgeStmt =
      EdgeStmt(
        List(dotA, dotB, dotC, dotD),
        Nil
      )
    Arrow.resetId()
    val expanded = edgeToViewerArrows(edgeStmt)
    val expected =
      List(
        Arrow(a, b, Attributes.empty, 1),
        Arrow(b, c, Attributes.empty, 2),
        Arrow(c, d, Attributes.empty, 3)
      )
    assertEquals(expanded, expected)
  }

  test("expandArrows should process a node and a group (case 3)") {
    val edgeStmt =
      EdgeStmt(
        List(
          dotA,
          SubGraph(List(NodeStmt(dotB, Nil), NodeStmt(dotC)), None)
        ),
        List(Attr("id", AttrValue("1")))
      )
    Arrow.resetId()
    val expanded = edgeToViewerArrows(edgeStmt)
    val expected =
      List(
        Arrow(a, b, Attributes.of(Id -> "1"), 1),
        Arrow(a, c, Attributes.of(Id -> "1"), 2)
      )
    // Flaky test
    assertEquals(expanded, expected)
  }

  test("expandArrows should process a group and a node (case 4)") {
    val edgeStmt =
      EdgeStmt(
        List(
          SubGraph(List(NodeStmt(dotA), NodeStmt(dotB)), None),
          dotC
        ),
        List(Attr("id", AttrValue("1")))
      )
    Arrow.resetId()
    val expanded = edgeToViewerArrows(edgeStmt)
    val expected =
      List(
        Arrow(a, c, Attributes.of(Id -> "1"), 1),
        Arrow(b, c, Attributes.of(Id -> "1"), 2)
      )
    // TODO: Flaky test
    assertEquals(expanded, expected)
  }

  test("expandArrows should process a group and a group (case 5)") {
    val edgeStmt =
      EdgeStmt(
        List(
          SubGraph(List(NodeStmt(dotA), NodeStmt(dotB)), None),
          SubGraph(List(NodeStmt(dotC), NodeStmt(dotD)), None)
        ),
        List(Attr("id", AttrValue("1")))
      )
    Arrow.resetId()
    val expanded = edgeToViewerArrows(edgeStmt)
    val expected =
      List(
        Arrow(a, c, Attributes.of(Id -> "1"), 1),
        Arrow(a, d, Attributes.of(Id -> "1"), 2),
        Arrow(b, c, Attributes.of(Id -> "1"), 3),
        Arrow(b, d, Attributes.of(Id -> "1"), 4)
      )
    assertEquals(expanded, expected)
  }
}
