package org.jpablo.graphexplorer.viewer.formats.dot.ast

import munit.ScalaCheckSuite
import org.jpablo.graphexplorer.viewer.formats.dot.ast.viewerGraph.graphDataToDotGraphElements
import org.jpablo.graphexplorer.viewer.graph.ViewerGraphElements
import org.jpablo.graphexplorer.viewer.models.Arrow.arrow
import org.jpablo.graphexplorer.viewer.models.ViewerNode.node
import org.jpablo.graphexplorer.viewer.models.*

class ToFlattenedElementsSpec extends ScalaCheckSuite:

  val rootId = ViewerGraphElements.defaultRootId
  val group0 = GroupId("cluster_0")
  val group1 = GroupId("cluster_1")

  test("toFlattenedElements should return all nodes") {
    val data = astWithNestedSubGraphs.toFlattenedElements
    val expectedNodes =
      List(
        node("a"),
        node("b"),
        node("z", Map(AttributeId("label") -> AttrValue("ZZ"))),
        node("d")
      )
    assertEquals(data.nodes, expectedNodes)
  }

  test("toFlattenedElements should return all arrows") {
    EdgeStmt.resetId()
    val data = astWithNestedSubGraphs.toFlattenedElements
    val expectedArrows =
      List(
        arrow("x" -> "y", seq = 1),
        arrow("a" -> "b", seq = 2),
        arrow("x" -> "a", seq = 3),
        arrow("b" -> "c", seq = 4)
      )
    assertEquals(data.arrows, expectedArrows)
  }

  test("toFlattenedElements should return all groups") {
    val data = astWithNestedSubGraphs.toFlattenedElements
    val expectedGroups =
      List(
        ViewerGroup(group0, nodeAttrs = Attributes(Map(AttributeId("shape") -> AttrValue("egg")))),
        ViewerGroup(group1),
        ViewerGroup(rootId)
      )
    assertEquals(data.groups, expectedGroups)
  }

  test("toFlattenedElements in empty graphs should find a single group (the root group)") {
    val emptyAST = DotAST(tpe = "digraph", children = Nil, id = Some(rootId.value))
    val data = emptyAST.toFlattenedElements
    val expected =
      FlattenedGraphElement(
        rootId      = rootId,
        arrows      = Nil,
        groups      = List(ViewerGroup(rootId)),
        nodes       = Nil,
        memberships = Nil
      )

    assertEquals(data, expected)
  }

  test("toFlattenedElements should return all memberships") {
    EdgeStmt.resetId()
    val data = astWithNestedSubGraphs.toFlattenedElements
    val expectedMemberships =
      List(
        NodeId("z")       -> group0,
        ArrowId("a->b:2") -> group0,
        group1            -> group0,
        NodeId("d")       -> group1
      )

    assertEquals(data.memberships, expectedMemberships)
  }

  test("roundtrip (toFlattenedElements -> graphDataToDotGraphElements) should produce equivalent elements") {
    EdgeStmt.resetId()
    val flattened = astWithNestedSubGraphs.toFlattenedElements
    val data = ViewerGraphElements.from(flattened)
    val reconstructed = graphDataToDotGraphElements(data)
    val x = DotNodeId("x")
    val a = DotNodeId("a")
    val y = DotNodeId("y")
    val b = DotNodeId("b")
    val c = DotNodeId("c")
    val d = DotNodeId("d")
    val z = DotNodeId("z")
    // This is not the same as the original AST, but should render to something similar
    // - Some elements like Newline and Pad are removed
    // - Order of elements may change: SubGraphs -> NodeStmts -> EdgeStmts
    val expected =
      List(
        SubGraph(
          List(
            AttrStmt("node", List(Attr("shape", AttrValue("egg")))),
            SubGraph(List(NodeStmt(d)), Some("cluster_1")),
            NodeStmt(z, List(Attr("label", AttrValue("ZZ")))),
            EdgeStmt(List(a, b), List(Attr("id", AttrValue("2"))))
          ),
          Some("cluster_0")
        ),
        NodeStmt(x),
        NodeStmt(a),
        NodeStmt(y),
        NodeStmt(b),
        NodeStmt(c),
        EdgeStmt(List(x, y), List(Attr("id", AttrValue("1")))),
        EdgeStmt(List(x, a), List(Attr("id", AttrValue("3")))),
        EdgeStmt(List(b, c), List(Attr("id", AttrValue("4"))))
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
      NodeStmt(DotNodeId("a"), Nil),
      Newline(),
      Pad(),
      NodeStmt(DotNodeId("b"), Nil),
      Newline(),
      Pad(),
      EdgeStmt(List(DotNodeId("x"), DotNodeId("y")), Nil),
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
          NodeStmt(DotNodeId("z"), List(Attr("label", AttrValue("ZZ")))),
          Newline(),
          Pad(),
          EdgeStmt(List(DotNodeId("a"), DotNodeId("b")), Nil),
          Newline(),
          Pad(),
          SubGraph(
            List(Newline(), Pad(), NodeStmt(DotNodeId("d"), Nil), Newline(), Pad()),
            Some("cluster_1")
          ),
          Newline(),
          Pad()
        ),
        Some("cluster_0")
      ),
      Newline(),
      Pad(),
      EdgeStmt(List(DotNodeId("x"), DotNodeId("a")), Nil),
      Newline(),
      Pad(),
      EdgeStmt(List(DotNodeId("b"), DotNodeId("c")), Nil),
      Newline()
    ),
    Some("G")
  )
