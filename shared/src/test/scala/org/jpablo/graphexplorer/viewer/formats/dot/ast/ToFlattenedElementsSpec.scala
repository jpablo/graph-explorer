package org.jpablo.graphexplorer.viewer.formats.dot.ast

import munit.ScalaCheckSuite
import org.jpablo.graphexplorer.viewer.formats.dot.ast.EdgeStmt.resetId
import org.jpablo.graphexplorer.viewer.formats.dot.ast.viewerGraph.graphDataToDotGraphElements
import org.jpablo.graphexplorer.viewer.graph.ViewerGraphData
import org.jpablo.graphexplorer.viewer.models.Arrow.arrow
import org.jpablo.graphexplorer.viewer.models.ViewerNode.node
import org.jpablo.graphexplorer.viewer.models.*

class ToFlattenedElementsSpec extends ScalaCheckSuite:

  val rootId = ViewerGraphData.defaultRootId
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
    resetId()
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
    resetId()
    val data = astWithNestedSubGraphs.toFlattenedElements
    val expectedMemberships =
      List(
        NodeId("z")      -> group0,
        NodeId("a->b:2") -> group0,
        group1           -> group0,
        NodeId("d")      -> group1
      )

    assertEquals(data.memberships, expectedMemberships)
  }

  test("roundtrip") {
    resetId()
    val flattened = astWithNestedSubGraphs.toFlattenedElements
    val data = ViewerGraphData.from(flattened)
    val reconstructed = graphDataToDotGraphElements(data)
    val expected =
      List(
        SubGraph(
          List(
            AttrStmt("node", List(Attr("shape", AttrValue("egg")))),
            SubGraph(List(NodeStmt(DotNodeId("d", None), Nil)), Some("cluster_1")),
            NodeStmt(DotNodeId("z", None), List(Attr("label", AttrValue("ZZ")))),
            EdgeStmt(List(DotNodeId("a", None), DotNodeId("b", None)), List(Attr("id", AttrValue("2"))))
          ),
          Some("cluster_0")
        ),
        NodeStmt(DotNodeId("x", None), Nil),
        NodeStmt(DotNodeId("a", None), Nil),
        NodeStmt(DotNodeId("y", None), Nil),
        NodeStmt(DotNodeId("b", None), Nil),
        NodeStmt(DotNodeId("c", None), Nil),
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
      NodeStmt(DotNodeId("a", None), Nil),
      Newline(),
      Pad(),
      NodeStmt(DotNodeId("b", None), Nil),
      Newline(),
      Pad(),
      EdgeStmt(List(DotNodeId("x", None), DotNodeId("y", None)), Nil),
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
          EdgeStmt(List(DotNodeId("a", None), DotNodeId("b", None)), Nil),
          Newline(),
          Pad(),
          SubGraph(
            List(Newline(), Pad(), NodeStmt(DotNodeId("d", None), Nil), Newline(), Pad()),
            Some("cluster_1")
          ),
          Newline(),
          Pad()
        ),
        Some("cluster_0")
      ),
      Newline(),
      Pad(),
      EdgeStmt(List(DotNodeId("x", None), DotNodeId("a", None)), Nil),
      Newline(),
      Pad(),
      EdgeStmt(List(DotNodeId("b", None), DotNodeId("c", None)), Nil),
      Newline()
    ),
    Some("G")
  )
