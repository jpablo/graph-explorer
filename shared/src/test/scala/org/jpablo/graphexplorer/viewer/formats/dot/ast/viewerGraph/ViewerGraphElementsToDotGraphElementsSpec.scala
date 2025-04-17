package org.jpablo.graphexplorer.viewer.formats.dot.ast.viewerGraph

import munit.ScalaCheckSuite
import org.jpablo.graphexplorer.viewer.formats.dot.ast.*
import org.jpablo.graphexplorer.viewer.formats.dot.ast.viewerGraph.viewerGraphElementsToDotGraphElements
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.GraphType.digraph
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Id
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.ViewerGroup.defaultGroupAttributes
import org.jpablo.graphexplorer.viewer.models.ViewerNode.defaultNodeAttributes

class ViewerGraphElementsToDotGraphElementsSpec extends ScalaCheckSuite:

  val group0 = GroupId("0")
  val group1 = GroupId("1")

  val defaultNodeDotAttrs = defaultNodeAttributes.toDotAttr

  def defaultGroupAttrStmt(gId: GroupId) = AttrStmt("graph", (defaultGroupAttributes + (Id -> gId.toSvg)).toDotAttr)

  val a = DotNodeId("a")
  val b = DotNodeId("b")
  val c = DotNodeId("c")
  val d = DotNodeId("d")
  val x = DotNodeId("x")
  val y = DotNodeId("y")
  val z = DotNodeId("z")

  val shapeEgg = Attr("shape", "egg")
  val labelZZ  = Attr("label", "ZZ")
  def idAttr(a: DotNodeId, b: DotNodeId, seq: Int) =
    Attr("id", Arrow(a.toNodeId, b.toNodeId, seq = seq).toSvg)

  val dotAST =
    DotAST(
      digraph.toString,
      List(
        NodeStmt(a),
        NodeStmt(b),
        EdgeStmt(List(x, y)),
        SubGraph(
          List(
            AttrStmt("node", List(shapeEgg)),
            NodeStmt(z, List(labelZZ)),
            EdgeStmt(List(a, b)),
            SubGraph(List(NodeStmt(d)), Some(group1.toDot))
          ),
          Some(group0.toDot)
        ),
        EdgeStmt(List(x, a)),
        EdgeStmt(List(b, c))
      ),
      Some("G")
    )

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
            defaultGroupAttrStmt(group0),
            SubGraph(List(defaultGroupAttrStmt(group1), NodeStmt(d, List(shapeEgg, d.toAttr))), Some(group1.toDot)),
            NodeStmt(a, List(shapeEgg, a.toAttr)),
            NodeStmt(b, List(shapeEgg, b.toAttr)),
            NodeStmt(z, List(shapeEgg, labelZZ, z.toAttr))
          ),
          Some(group0.toDot)
        ),
        NodeStmt(x, List(x.toAttr)),
        NodeStmt(y, List(y.toAttr)),
        NodeStmt(c, List(c.toAttr)),
        EdgeStmt(List(x, y), List(idAttr(x, y, 1))),
        EdgeStmt(List(a, b), List(idAttr(a, b, 2))), // This edge was originally in the subgraph but now at the top level
        EdgeStmt(List(x, a), List(idAttr(x, a, 3))),
        EdgeStmt(List(b, c), List(idAttr(b, c, 4)))
      )

    assertEquals(reconstructed, expected)
  }
