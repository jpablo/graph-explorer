package org.jpablo.graphexplorer.viewer.formats.dot.ast.viewerGraph

import munit.ScalaCheckSuite
import org.jpablo.graphexplorer.viewer.formats.dot.ast.*
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, ViewerGraphData}
import org.jpablo.graphexplorer.viewer.models.*

class ViewerGraphDotSpec extends ScalaCheckSuite:
  val rootId = ViewerGraphData.defaultRootId

  test("graphToDotAST should convert a ViewerGraph to a DotAST") {
    val arrow = Arrow(NodeId("a"), NodeId("b"), seq = 1)
    val graph =
      ViewerGraph(
        id = rootId.value,
        data = ViewerGraphData(
          rootId      = rootId,
          arrows      = Map(arrow.id -> arrow),
          groups      = Map(rootId -> ViewerGroup(rootId, Attributes(Map(AttributeId("label") -> AttrValue("Title"))))),
          nodes       = Map(),
          memberships = Map()
        ),
        tpe = "digraph"
      )

    val ast = graphToDotAST(graph)

    val expected =
      DotAST(
        "digraph",
        List(
          AttrStmt("graph", List(Attr("label", AttrValue("Title")))),
          EdgeStmt(List(DotNodeId("a", None), DotNodeId("b", None)), List(Attr("id", AttrValue("1"))))
        ),
        Some("G")
      )
    pprint.log(ast, showFieldNames = false)

    assertEquals(ast, expected)
  }
