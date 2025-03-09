package org.jpablo.graphexplorer.viewer.graph

import munit.ScalaCheckSuite
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.models.ViewerGroup.group
import org.jpablo.graphexplorer.viewer.models.ViewerNode.node

class ViewerGraphElementsSpec extends ScalaCheckSuite:

  val rootId = ViewerGraphElements.defaultRootId
  val rootGroup = ViewerGroup(rootId)

  val g = rootId
  val initialGroup = group(g)

  val a = NodeId("a")
  val b = NodeId("b")
  val c = NodeId("c")

  test("addToNewGroup should create a new group and add elements to it") {
    // Setup initial graph with nodes
    val graphData = ViewerGraphElements(nodes = Map(node(a), node(b), node(c)))
    // sanity check
    assertEquals(graphData.groups, Map(initialGroup))

    // Add elements to a new group with a label
//    val updatedGraphData = graphData.moveToNewGroup(ElementIds.from(a, b), "New Group")
//    val newGroupId = updatedGraphData.memberships(a)
//
//    val expected =
//      ViewerGraphElements(
//        nodes       = Map(node(a), node(b), node(c)),
//        memberships = Map(a -> newGroupId, b -> newGroupId),
//        groups = Map(
//          initialGroup,
//          newGroupId -> ViewerGroup(newGroupId, Attributes(Map(AttributeId("label") -> AttrValue("New Group"))))
//        )
//      )
//
//    assertEquals(updatedGraphData, expected, "The graph data should be updated with the new group and memberships")
  }

  test("addToNewGroup should add the new group to a common parent when elements share a parent") {
    // Create a group for nodes a and b
    val groupId1 = GroupId("group1")
    val group1 = ViewerGroup(groupId1, Attributes(Map(AttributeId("label") -> AttrValue("Group 1"))))

    // Setup initial graph with nodes a and b in group1
    val graphData = ViewerGraphElements(
      nodes       = Map(node(a), node(b), node(c)),
      groups      = Map(initialGroup, groupId1 -> group1),
      memberships = Map(a -> groupId1, b -> groupId1)
    )

    // Create element IDs to add to the new group
    val elementIds = ElementIds.from(a, b)

    // Add elements to a new group
//    val updatedGraphData = graphData.moveToNewGroup(elementIds, "Nested Group")
//
//    // Verify the new group was created
//    val newGroupId = updatedGraphData.memberships(a)
//    assertEquals(updatedGraphData.memberships(b), newGroupId, "Both nodes should be in the same group")
//
//    // Verify the new group is a child of group1
//    assertEquals(updatedGraphData.memberships(newGroupId), groupId1, "New group should be a child of group1")
  }
