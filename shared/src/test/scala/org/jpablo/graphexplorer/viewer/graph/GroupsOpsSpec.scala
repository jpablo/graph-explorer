package org.jpablo.graphexplorer.viewer.graph

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Label
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.ViewerGroup.{group, groupWithId}
import org.jpablo.graphexplorer.viewer.models.ViewerNode.nodeWithId

class GroupsOpsSpec extends FunSuite:

  val rootId = ViewerGraphElements.defaultRootId
  val rootGroup = group(rootId)

  val g = rootId
  val initialGroup = groupWithId(g)

  val a = NodeId("a")
  val b = NodeId("b")
  val c = NodeId("c")

  test("moveToNewGroup should create a new group and add elements to it") {
    val graph = ViewerGraph(ViewerGraphElements(nodes = Map(nodeWithId(a), nodeWithId(b), nodeWithId(c))))
    // sanity check
    assert(graph.groups == Map.empty)
    assert(graph.memberships.isEmpty)

    // Add elements to a new group with a label
    val updatedGraph = graph.moveToNewGroup("New Group", a, b)

    val newGroupId = updatedGraph.membership(a).get
    val newGroup =
      newGroupId -> group(newGroupId, Attributes.of(Label -> "New Group"))

    val expected =
      ViewerGraph.minimal.modifyElements.using(
        _.copy(
          nodes       = Map(nodeWithId(a), nodeWithId(b), nodeWithId(c)),
          memberships = Map(a -> newGroupId, b -> newGroupId),
          groups      = Map(newGroup)
        )
      )
    assertEquals(updatedGraph, expected, "The graph should be updated with the new group and memberships")
  }

  test("moveToNewGroup should add the new group to a common parent when elements share a parent") {
    val graphWithGroup =
      ViewerGraph(ViewerGraphElements(nodes = Map(nodeWithId(a), nodeWithId(b), nodeWithId(c))))
        .moveToNewGroup("Parent group", a, b)

    val parentGroup = graphWithGroup.membership(a).get

    // Add elements to a new group
    val updatedGraph = graphWithGroup.moveToNewGroup("Nested Group", a, b)

    // Verify the new group was created
    val newGroup = updatedGraph.membership(a).get
    assertEquals(updatedGraph.memberships(b), newGroup, "Both nodes should be in the same group")

    // Verify the new group is a child of group1
    assertEquals(updatedGraph.memberships(newGroup), parentGroup, "New group should be a child of group1")
  }

  test("moveToGroup should move nodes to an existing group") {
    // Create a graph with nodes and a group
    val graph = ViewerGraph(ViewerGraphElements(nodes = Map(nodeWithId(a), nodeWithId(b), nodeWithId(c))))
    val graphWithGroup = graph.moveToNewGroup("Group 1", c)
    val groupId = graphWithGroup.membership(c).get

    // Move nodes to the existing group
    val updatedGraph = graphWithGroup.moveToGroup(groupId, Seq(a, b))

    // Verify nodes were moved to the group
    val expected = Map[GroupMemberId, GroupId](a -> groupId, b -> groupId, c -> groupId)
    assertEquals(updatedGraph.memberships, expected, "All nodes should be in the group")
  }

  test("ungroupSelection should move elements to their grandparent group") {
    // Create a nested group structure
    val graph =
      ViewerGraph(ViewerGraphElements(nodes = Map(nodeWithId(a), nodeWithId(b), nodeWithId(c))))
        .moveToNewGroup("Parent Group", a, b, c)

    val parentGroupId = graph.membership(a).get

    val graphWithNestedGroup = graph.moveToNewGroup("Nested Group", a, b)
    val nestedGroupId = graphWithNestedGroup.membership(a).get

    assertEquals(
      graphWithNestedGroup.memberships,
      Map[GroupMemberId, GroupId](a -> nestedGroupId, b -> nestedGroupId) ++ Map(c -> parentGroupId, nestedGroupId -> parentGroupId),
      "Verify initial group structure"
    )

    // Ungroup selection
    val updatedGraph = graphWithNestedGroup.ungroupSelection(ElementIds.from(a, b))

    // Verify nodes were moved to parent group
    assertEquals(
      updatedGraph.memberships,
      Map[GroupMemberId, GroupId](a -> parentGroupId, b -> parentGroupId, c -> parentGroupId, nestedGroupId -> parentGroupId),
      "Nodes a, b should be moved to parent group"
    )
  }

  test("getDirectChildren should return direct children of a group") {
    // Create a nested group structure
    val graph = ViewerGraph(ViewerGraphElements(nodes = Map(nodeWithId(a), nodeWithId(b), nodeWithId(c))))
    val graphWithParentGroup = graph.moveToNewGroup("Parent Group", a, b, c)
    val parentGroupId = graphWithParentGroup.membership(a).get

    val graphWithNestedGroup = graphWithParentGroup.moveToNewGroup("Nested Group", a, b)
    val nestedGroupId = graphWithNestedGroup.membership(a).get

    // Get direct children of parent group
    val parentGroupChildren = graphWithNestedGroup.getDirectChildren(Set(parentGroupId))

    // Verify direct children
    assertEquals(
      parentGroupChildren,
      Set(c, nestedGroupId),
      "Direct children of parent group should be c and nested group"
    )
  }

  test("getDirectChildren should include elements without explicit membership when root group is specified") {
    // Create a graph with some nodes in groups and some not
    val graph = ViewerGraph(ViewerGraphElements(nodes = Map(nodeWithId(a), nodeWithId(b), nodeWithId(c))))
    val updatedGraph = graph.moveToNewGroup("Group 1", a, b)

    // Get direct children of root group
    val rootChildren = updatedGraph.getRootChildren

    // Verify direct children of root
    val group1 = updatedGraph.membership(a).get
    assertEquals(rootChildren, Set(c, group1), "Direct children of root group should include c and group containing a")
    assertEquals(updatedGraph.membership(c), None, "Node b should not be included as it is in a group")
    assertEquals(updatedGraph.membership(group1), None, "Node b should not be included as it is in a group")
  }

  test("getAllChildren should return all nested children of a group") {
    // Create a nested group structure
    val graph = ViewerGraph(ViewerGraphElements(nodes = Map(nodeWithId(a), nodeWithId(b), nodeWithId(c))))
    val graphWithParentGroup = graph.moveToNewGroup("Parent Group", a, b, c)
    val parentGroupId = graphWithParentGroup.membership(a).get

    val graphWithNestedGroup = graphWithParentGroup.moveToNewGroup("Nested Group", a, b)
    val nestedGroupId = graphWithNestedGroup.membership(a).get

    // Get all children of parent group
    val allChildren = graphWithNestedGroup.getAllChildren(Set(parentGroupId))

    // Verify all children
    assertEquals(allChildren, Set(a, b, c, nestedGroupId))
  }
