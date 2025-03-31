package org.jpablo.graphexplorer.viewer.graph

import munit.ScalaCheckSuite
import org.jpablo.graphexplorer.viewer.formats.dot.ast.*
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.ViewerGroup.groupWithId
import org.jpablo.graphexplorer.viewer.models.ViewerNode.nodeWithId

class ViewerGraphElementsSpec extends ScalaCheckSuite:

  val rootId       = ViewerGraphElements.defaultRootId
  val initialGroup = groupWithId(rootId)
  val a            = DotNodeId("a")
  val b            = DotNodeId("b")
  val c            = DotNodeId("c")
  val d            = DotNodeId("d")
  val x            = DotNodeId("x")
  val y            = DotNodeId("y")
  val z            = DotNodeId("z")

  test("Constructor should create a ViewerGraphElements with default values") {
    // Setup initial graph with nodes
    val graphData = ViewerGraphElements(nodes = Map(nodeWithId(NodeId("a"))))
    // sanity check
    assertEquals(graphData.groups, Map(initialGroup))
  }

  test("Constructor should enforce that the root group is present in the groups but not in memberships") {
    val graphData = ViewerGraphElements(nodes = Map(nodeWithId(NodeId("a"))))
    assert(graphData.groups.contains(rootId), "Root group should be present in the groups")
    assert(!graphData.memberships.contains(rootId), "Root group should not be in memberships")
  }
