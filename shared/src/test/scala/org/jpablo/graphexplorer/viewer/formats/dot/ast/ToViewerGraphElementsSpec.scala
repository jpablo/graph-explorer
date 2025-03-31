package org.jpablo.graphexplorer.viewer.formats.dot.ast

import munit.ScalaCheckSuite
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.GraphType.digraph
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Shape
import org.jpablo.graphexplorer.viewer.graph.ViewerGraphElements
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.Arrow.arrow
import org.jpablo.graphexplorer.viewer.models.ViewerGroup.{defaultGroupAttributes, group}
import org.jpablo.graphexplorer.viewer.models.ViewerNode.{defaultNodeAttributes, nodeWithId}

class ToViewerGraphElementsSpec extends ScalaCheckSuite:

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

  EdgeStmt.resetId()
  val viewerGraphElements = astWithNestedSubGraphs.toViewerGraphElements

  test("toViewerGraphElements should return all nodes") {
    val expectedNodes =
      Map(
        a.nodeTuple,
        b.nodeTuple,
        c.nodeTuple,
        d.nodeTuple,
        x.nodeTuple,
        y.nodeTuple,
        nodeWithId("z", "label" -> "ZZ")
      )
    assertEquals(viewerGraphElements.nodes, expectedNodes)
  }

  test("toViewerGraphElements should return all arrows") {
    // flaky


    val expectedArrows =
      List(
        arrow("x" -> "y", seq = 1),
        arrow("a" -> "b", seq = 2),
        arrow("x" -> "a", seq = 3),
        arrow("b" -> "c", seq = 4)
      ).map(a => a.id -> a).toMap

    assertEquals(viewerGraphElements.arrows, expectedArrows)
  }

  test("toViewerGraphElements should return all groups") {
    val expectedGroups =
      List(
        group(group0, nodeAttrs = Attributes.of(Shape -> Shape.egg)),
        group(group1),
        group(rootId)
      ).map(a => a.id -> a).toMap

    assertEquals(viewerGraphElements.groups, expectedGroups)
  }

  test("toViewerGraphElements in empty graphs should find a single group (the root group)") {
    val emptyAST = DotAST(tpe = digraph.toString, children = Nil, id = Some(rootId.value))
    val emptyVGE = emptyAST.toViewerGraphElements
    val expected = ViewerGraphElements(rootId = rootId, groups = Map(rootId -> group(rootId)))

    assertEquals(emptyVGE, expected)
  }

  test("toViewerGraphElements should return all memberships") {
    EdgeStmt.resetId()
    val expectedMemberships =
      List(
        NodeId("z") -> group0,
        group1      -> group0,
        NodeId("d") -> group1
      ).toMap

    assertEquals(viewerGraphElements.memberships, expectedMemberships)
  }

  lazy val astWithNestedSubGraphs =
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
