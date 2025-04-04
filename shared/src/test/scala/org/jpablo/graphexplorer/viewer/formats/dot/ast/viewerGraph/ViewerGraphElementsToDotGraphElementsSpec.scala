package org.jpablo.graphexplorer.viewer.formats.dot.ast.viewerGraph

import munit.ScalaCheckSuite
import org.jpablo.graphexplorer.viewer.formats.dot.ast.*
import org.jpablo.graphexplorer.viewer.formats.dot.ast.viewerGraph.viewerGraphElementsToDotGraphElements
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.GraphType.digraph
import org.jpablo.graphexplorer.viewer.graph.ViewerGraphElements
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.ViewerGroup.defaultGroupAttributes
import org.jpablo.graphexplorer.viewer.models.ViewerNode.{defaultNodeAttributes, nodeWithId}

class ViewerGraphElementsToDotGraphElementsSpec extends ScalaCheckSuite:

  val rootId = ViewerGraphElements.defaultRootId
  val group0 = GroupId("cluster_0")
  val group1 = GroupId("cluster_1")

  val defaultNodeDotAttrs  = defaultNodeAttributes.toDotAttr
  val defaultGroupAttrStmt = AttrStmt("graph", defaultGroupAttributes.toDotAttr)

  val a = DotNodeId("a")
  val b = DotNodeId("b")
  val c = DotNodeId("c")
  val d = DotNodeId("d")
  val x = DotNodeId("x")
  val y = DotNodeId("y")
  val z = DotNodeId("z")

  extension (d: DotNodeId)
    def nodeTuple: (NodeId, ViewerNode) =
      nodeWithId(d.id)

  val dotAST =
    DotAST(
      digraph.toString,
      List(
        NodeStmt(a),
        NodeStmt(b),
        EdgeStmt(List(x, y)),
        SubGraph(
          List(
            AttrStmt("node", List(Attr("shape", "egg"))),
            NodeStmt(z, List(Attr("label", "ZZ"))),
            EdgeStmt(List(a, b)),
            SubGraph(List(NodeStmt(d)), Some("cluster_1"))
          ),
          Some("cluster_0")
        ),
        EdgeStmt(List(x, a)),
        EdgeStmt(List(b, c))
      ),
      Some("G")
    )

  EdgeStmt.resetId()
  val viewerGraphElements = dotAST.toViewerGraphElements

  test("roundtrip (toViewerGraphElements -> viewerGraphElementsToDotGraphElements) should produce equivalent elements") {
    val reconstructed = viewerGraphElementsToDotGraphElements(viewerGraphElements)

    // This is not the same as the original AST, but should render to something similar
    // - Some elements like Newline and Pad are removed
    // - Order of elements may change: SubGraphs -> NodeStmts -> EdgeStmts
    // - Edges are pulled out of SubGraphs and added to the top level list
    // - "implicit" nodes (only referenced by edges) may be added to the top level if they are not already present in the nodes list

    val expected =
      List(
        SubGraph(
          List(
            defaultGroupAttrStmt,
            SubGraph(List(defaultGroupAttrStmt, NodeStmt(d)), Some("cluster_1")),
            NodeStmt(a),
            NodeStmt(b),
            NodeStmt(z, List(Attr("shape", "egg"), Attr("label", "ZZ")))
          ),
          Some("cluster_0")
        ),
        NodeStmt(x),
        NodeStmt(y),
        NodeStmt(c),
        EdgeStmt(List(x, y), List(Attr("id", "1"))),
        EdgeStmt(List(a, b), List(Attr("id", "2"))), // This edge was originally in the subgraph but now at the top level
        EdgeStmt(List(x, a), List(Attr("id", "3"))),
        EdgeStmt(List(b, c), List(Attr("id", "4")))
      )

    assertEquals(reconstructed, expected)
  }
