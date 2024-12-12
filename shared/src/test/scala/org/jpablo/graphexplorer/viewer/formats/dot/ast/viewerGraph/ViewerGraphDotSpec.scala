package org.jpablo.graphexplorer.viewer.formats.dot.ast.viewerGraph

import munit.ScalaCheckSuite
import org.jpablo.graphexplorer.viewer.formats.dot.ast.*
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, ViewerGraphData}
import org.jpablo.graphexplorer.viewer.models.*

class ViewerGraphDotSpec extends ScalaCheckSuite:
  val rootId = ViewerGraph.defaultRootId

  test("graphToDotAST should convert a ViewerGraph to a DotAST") {
    val graph =
      ViewerGraph(
        id = "G",
        data = ViewerGraphData(
          rootId = rootId,
          arrows = Map(NodeId("a->b:0") -> Arrow(NodeId("a"), NodeId("b"), Attributes(Map("id" -> AttrValue("1"))), 0)),
          groups = Map(
            rootId -> ViewerGroup(
              rootId,
              Attributes(Map("label" -> AttrValue("Title"))),
              Attributes(Map()),
              Attributes(Map())
            )
          ),
          nodes       = Map(),
          memberships = Map(NodeId("a->b:0") -> rootId)
        ),
        tpe = "digraph"
      )

    val ast = graphToDotAST(graph)

    val expected =
      DotAST(
        "digraph",
        List(
          AttrStmt("graph", List(Attr("label", AttrValue("Title")))),
          EdgeStmt(List(DotNodeId("a", None), DotNodeId("b", None)), List())
        ),
        Some("G")
      )
    pprint.log(ast, showFieldNames = false)

    assertEquals(ast, expected)
  }
