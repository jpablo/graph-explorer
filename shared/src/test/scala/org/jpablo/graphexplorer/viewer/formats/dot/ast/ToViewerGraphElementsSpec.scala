package org.jpablo.graphexplorer.viewer.formats.dot.ast

import munit.ScalaCheckSuite
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.GraphType.digraph
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Id, Shape}
import org.jpablo.graphexplorer.viewer.graph.ViewerGraphElements
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.Arrow.arrow
import org.jpablo.graphexplorer.viewer.models.ViewerGroup.{defaultGroupAttributes, group}
import org.jpablo.graphexplorer.viewer.models.ViewerNode.{defaultNodeAttributes, nodeNoDefaults, nodeWithId}

import scala.collection.immutable.VectorMap

class ToViewerGraphElementsSpec extends ScalaCheckSuite:

  val rootId = ViewerGraphElements.defaultRootId
  val group0 = GroupId("0")
  val group1 = GroupId("1")

  val defaultNodeDotAttrs  = defaultNodeAttributes.toDotAttr
  val defaultGroupAttrStmt = AttrStmt("graph", defaultGroupAttributes.toDotAttr)

  val a = DotNodeId("a")
  val b = DotNodeId("b")
  val c = DotNodeId("c")
  val d = DotNodeId("d")
  val x = DotNodeId("x")
  val y = DotNodeId("y")
  val z = DotNodeId("z")

  val shapeEgg = Attributes.of(Shape -> Shape.egg)

  def groupIdAttr(gId: GroupId) = Id -> gId.toSvg

  extension (d: DotNodeId)
    def nodeTuple(attrs: Attributes = Attributes.empty): (NodeId, ViewerNode) =
      val nId = NodeId(d.id)
      nId -> nodeNoDefaults(nId, attrs)

  val viewerGraphElements = astWithNestedSubGraphs.toViewerGraphElements

  test("toViewerGraphElements should return all nodes") {
    val expectedNodes =
      VectorMap(
        a.nodeTuple(shapeEgg),
        b.nodeTuple(shapeEgg),
        nodeWithId("z", "shape" -> "egg", "label" -> "ZZ"),
        d.nodeTuple(shapeEgg),
        x.nodeTuple(),
        y.nodeTuple(),
        c.nodeTuple()
      )
    assertEquals(viewerGraphElements.nodes, expectedNodes)
  }

  test("toViewerGraphElements should return all arrows") {
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
        group(group0, attributes = Attributes.of(Shape -> Shape.triangle)),
        group(group1)
      ).map(a => a.id -> a).toMap

    assertEquals(viewerGraphElements.groups, expectedGroups)
  }

  test("toViewerGraphElements in empty graphs should find a single group (the root group)") {
    val emptyAST = DotAST(tpe = digraph.toString, children = Nil, id = Some(rootId.value))
    val emptyVGE = emptyAST.toViewerGraphElements
    val expected = ViewerGraphElements()

    assertEquals(emptyVGE, expected)
  }

  test("toViewerGraphElements should return all memberships") {
    val expectedMemberships =
      List(
        NodeId("z") -> group0,
        NodeId("a") -> group0,
        NodeId("b") -> group0,
        group1      -> group0,
        NodeId("d") -> group1
      ).toMap

    assertEquals(viewerGraphElements.memberships, expectedMemberships)
  }

  lazy val astWithNestedSubGraphs =
    DotAST(
      digraph.toString,
      List(
        AttrStmt("node", List(Attr("shape", AttrValue("trapezium")))),
        NodeStmt(a),
        NodeStmt(b),
        EdgeStmt(List(x, y)),
        SubGraph(
          List(
            // this will be ignored (only the top level node and edge attributes are kept as defaults)
            AttrStmt("node", List(Attr("shape", "egg"))),
            AttrStmt("graph", List(Attr("shape", "triangle"))), // this will be kept (group attributes)
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
