package org.jpablo.graphexplorer.viewer.graph

import munit.ScalaCheckSuite
import org.jpablo.graphexplorer.viewer.formats.dot.ast.nodeWithId
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.ViewerGroup.group

class ViewerGraphElementsSpec extends ScalaCheckSuite:

  val rootId = ViewerGraphElements.defaultRootId
  val initialGroup = group(rootId)
  val a = NodeId("a")

  test("Constructor should create a ViewerGraphElements with default values") {
    // Setup initial graph with nodes
    val graphData = ViewerGraphElements(nodes = Map(nodeWithId(a)))
    // sanity check
    assertEquals(graphData.groups, Map(initialGroup))
  }

  test("Constructor should enforce that the root group is present in the groups but not in memberships") {
    val graphData = ViewerGraphElements(nodes = Map(nodeWithId(a)))
    assert(graphData.groups.contains(rootId), "Root group should be present in the groups")
    assert(!graphData.memberships.contains(rootId), "Root group should not be in memberships")
  }
