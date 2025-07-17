package org.jpablo.graphexplorer.viewer.graph

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Label
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.ViewerGroup.{group, groupWithId}
import org.jpablo.graphexplorer.viewer.models.ViewerNode.nodeWithId

import scala.collection.immutable.VectorMap

class GroupsOpsSpec extends FunSuite:

  val rootId    = ViewerGraphElements.defaultRootId
  val rootGroup = group(rootId)

  val g            = rootId
  val initialGroup = groupWithId(g)

  val a = NodeId("a")
  val b = NodeId("b")
  val c = NodeId("c")

  test("moveToNewGroup (without name) should create a new group with elements") {
    val graph = ViewerGraph(ViewerGraphElements(nodes = VectorMap(nodeWithId(a), nodeWithId(b), nodeWithId(c))))
    // sanity check
    assert(graph.groups == Map.empty)
    assert(graph.memberships.isEmpty)

    // Move elements to a new group with a label
    val updatedGraph = graph.moveToNewGroup(ElementIds.from(a))

    val newGroupId = updatedGraph.membership(a).get
    val newGroup =
      newGroupId -> group(newGroupId, Attributes.of(Label -> ""))

    val expected =
      ViewerGraph.minimal.modifyElements.using(
        _.copy(
          nodes = VectorMap(nodeWithId(a), nodeWithId(b), nodeWithId(c)),
          memberships = VectorMap(a -> newGroupId),
          groups = Map(newGroup)
        )
      )
    assertEquals(updatedGraph, expected, "The graph should be updated with the new group and memberships")
  }

  test("moveToNewGroup should create a new group and add elements to it") {
    val graph = ViewerGraph(ViewerGraphElements(nodes = VectorMap(nodeWithId(a), nodeWithId(b), nodeWithId(c))))
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
          nodes = VectorMap(nodeWithId(a), nodeWithId(b), nodeWithId(c)),
          memberships = VectorMap(a -> newGroupId, b -> newGroupId),
          groups = Map(newGroup)
        )
      )
    assertEquals(updatedGraph, expected, "The graph should be updated with the new group and memberships")
  }

  test("moveToNewGroup should add the new group to a common parent when elements share a parent") {
    val graphWithGroup =
      ViewerGraph(ViewerGraphElements(nodes = VectorMap(nodeWithId(a), nodeWithId(b), nodeWithId(c))))
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
    val graph          = ViewerGraph(ViewerGraphElements(nodes = VectorMap(nodeWithId(a), nodeWithId(b), nodeWithId(c))))
    val graphWithGroup = graph.moveToNewGroup("Group 1", c)
    val groupId        = graphWithGroup.membership(c).get

    // Move nodes to the existing group
    val updatedGraph = graphWithGroup.moveToGroup(groupId, Seq(a, b))

    // Verify nodes were moved to the group
    val expected = Map[GroupMemberId, GroupId](a -> groupId, b -> groupId, c -> groupId)
    assertEquals(updatedGraph.memberships, expected, "All nodes should be in the group")
  }

  test("ungroupSelection should move elements to their grandparent group") {
    // Create a nested group structure
    val graph =
      ViewerGraph(ViewerGraphElements(nodes = VectorMap(nodeWithId(a), nodeWithId(b), nodeWithId(c))))
        .moveToNewGroup("Parent Group", a, b, c)

    val parentGroupId = graph.membership(a).get

    val graphWithNestedGroup = graph.moveToNewGroup("Nested Group", a, b)
    val nestedGroupId        = graphWithNestedGroup.membership(a).get

    assertEquals(
      graphWithNestedGroup.memberships,
      Map[GroupMemberId, GroupId](a -> nestedGroupId, b -> nestedGroupId) ++ Map(c -> parentGroupId, nestedGroupId -> parentGroupId),
      "Verify initial group structure"
    )

    // Ungroup selection
    val updatedGraph = graphWithNestedGroup.ungroupSelection(ElementIds.from(a, b))

    // Verify nodes were moved to parent group and empty nested group was removed
    assertEquals(
      updatedGraph.memberships,
      Map[GroupMemberId, GroupId](a -> parentGroupId, b -> parentGroupId, c -> parentGroupId),
      "Nodes a, b should be moved to parent group and empty nested group should be removed"
    )
    
    // Verify the empty nested group was removed
    assert(!updatedGraph.groups.contains(nestedGroupId), "Empty nested group should be automatically removed")
  }

  test("getDirectChildren should return direct children of a group") {
    // Create a nested group structure
    val graph                = ViewerGraph(ViewerGraphElements(nodes = VectorMap(nodeWithId(a), nodeWithId(b), nodeWithId(c))))
    val graphWithParentGroup = graph.moveToNewGroup("Parent Group", a, b, c)
    val parentGroupId        = graphWithParentGroup.membership(a).get

    val graphWithNestedGroup = graphWithParentGroup.moveToNewGroup("Nested Group", a, b)
    val nestedGroupId        = graphWithNestedGroup.membership(a).get

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
    val graph        = ViewerGraph(ViewerGraphElements(nodes = VectorMap(nodeWithId(a), nodeWithId(b), nodeWithId(c))))
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
    val graph                = ViewerGraph(ViewerGraphElements(nodes = VectorMap(nodeWithId(a), nodeWithId(b), nodeWithId(c))))
    val graphWithParentGroup = graph.moveToNewGroup("Parent Group", a, b, c)
    val parentGroupId        = graphWithParentGroup.membership(a).get

    val graphWithNestedGroup = graphWithParentGroup.moveToNewGroup("Nested Group", a, b)
    val nestedGroupId        = graphWithNestedGroup.membership(a).get

    // Get all children of parent group
    val allChildren = graphWithNestedGroup.getAllChildren(Set(parentGroupId))

    // Verify all children
    assertEquals(allChildren, Set(a, b, c, nestedGroupId))
  }

  test("getEmptyGroups should identify groups with no children") {
    // Create a graph with nodes
    val graph = ViewerGraph(ViewerGraphElements(nodes = VectorMap(nodeWithId(a), nodeWithId(b), nodeWithId(c))))
    
    // Create a group with nodes
    val graphWithGroup = graph.moveToNewGroup("Group with nodes", a, b)
    val groupWithNodes = graphWithGroup.membership(a).get
    
    // Manually create an empty group
    val emptyGroupId = GroupId("empty_group")
    val emptyGroup = group(emptyGroupId, Attributes.of(Label -> "Empty Group"))
    val graphWithEmptyGroup = graphWithGroup.modifyElements.using(_.copy(
      groups = graphWithGroup.groups + (emptyGroupId -> emptyGroup)
    ))
    
    // Test getEmptyGroups
    val emptyGroups = graphWithEmptyGroup.getEmptyGroups()
    
    assertEquals(emptyGroups, Set(emptyGroupId), "Only the empty group should be identified as empty")
    assert(!emptyGroups.contains(groupWithNodes), "Group with nodes should not be considered empty")
  }

  test("removeEmptyGroups should remove groups with no children") {
    // Create a graph with nodes
    val graph = ViewerGraph(ViewerGraphElements(nodes = VectorMap(nodeWithId(a), nodeWithId(b), nodeWithId(c))))
    
    // Create a group with nodes
    val graphWithGroup = graph.moveToNewGroup("Group with nodes", a, b)
    val groupWithNodes = graphWithGroup.membership(a).get
    
    // Manually create an empty group
    val emptyGroupId = GroupId("empty_group")
    val emptyGroup = group(emptyGroupId, Attributes.of(Label -> "Empty Group"))
    val graphWithEmptyGroup = graphWithGroup.modifyElements.using(_.copy(
      groups = graphWithGroup.groups + (emptyGroupId -> emptyGroup)
    ))
    
    // Remove empty groups
    val cleanedGraph = graphWithEmptyGroup.removeEmptyGroups()
    
    // Verify empty group was removed
    assert(!cleanedGraph.groups.contains(emptyGroupId), "Empty group should be removed")
    assert(cleanedGraph.groups.contains(groupWithNodes), "Group with nodes should remain")
    assertEquals(cleanedGraph.memberships, graphWithGroup.memberships, "Memberships should remain unchanged")
  }

  test("removeEmptyGroups should recursively remove empty groups") {
    // Create a graph with nodes
    val graph = ViewerGraph(ViewerGraphElements(nodes = VectorMap(nodeWithId(a))))
    
    // Create a nested structure: parent -> child -> node a
    val graphWithChild = graph.moveToNewGroup("Child Group", a)
    val childGroupId = graphWithChild.membership(a).get
    
    val graphWithParent = graphWithChild.moveToNewGroup("Parent Group", childGroupId)
    val parentGroupId = graphWithParent.membership(childGroupId).get
    
    // Verify initial structure
    assertEquals(graphWithParent.memberships, Map[GroupMemberId, GroupId](
      a -> childGroupId,
      childGroupId -> parentGroupId
    ))
    
    // Remove node a, which should make child group empty, which should then make parent group empty
    val graphAfterRemoval = graphWithParent.removeElements(ElementIds.from(a))
    
    // Verify both empty groups were removed
    assert(!graphAfterRemoval.groups.contains(childGroupId), "Child group should be removed after node removal")
    assert(!graphAfterRemoval.groups.contains(parentGroupId), "Parent group should be removed after child group removal")
    assertEquals(graphAfterRemoval.memberships, Map.empty[GroupMemberId, GroupId], "All memberships should be removed")
  }

  test("removeElements should automatically clean up empty groups") {
    // Create a graph with a group containing one node
    val graph = ViewerGraph(ViewerGraphElements(nodes = VectorMap(nodeWithId(a), nodeWithId(b))))
    val graphWithGroup = graph.moveToNewGroup("Single Node Group", a)
    val groupId = graphWithGroup.membership(a).get
    
    // Verify initial state
    assert(graphWithGroup.groups.contains(groupId), "Group should exist initially")
    assertEquals(graphWithGroup.memberships(a), groupId, "Node should be in the group")
    
    // Remove the node
    val updatedGraph = graphWithGroup.removeElements(ElementIds.from(a))
    
    // Verify the group was automatically removed
    assert(!updatedGraph.groups.contains(groupId), "Empty group should be automatically removed")
    assert(!updatedGraph.nodes.contains(a), "Node should be removed")
    assert(!updatedGraph.memberships.contains(a), "Node membership should be removed")
    assertEquals(updatedGraph.nodes, Map(b -> nodeWithId(b)._2), "Other nodes should remain")
  }

  test("ungroupSelection should automatically clean up empty groups") {
    // Create a nested group structure where ungrouping will leave a group empty
    val graph = ViewerGraph(ViewerGraphElements(nodes = VectorMap(nodeWithId(a), nodeWithId(b))))
    
    // Create parent group with both nodes
    val graphWithParent = graph.moveToNewGroup("Parent Group", a, b)
    val parentGroupId = graphWithParent.membership(a).get
    
    // Create child group with only node a
    val graphWithChild = graphWithParent.moveToNewGroup("Child Group", a)
    val childGroupId = graphWithChild.membership(a).get
    
    // Verify initial state
    assertEquals(graphWithChild.memberships, Map[GroupMemberId, GroupId](
      a -> childGroupId,
      b -> parentGroupId,
      childGroupId -> parentGroupId
    ))
    
    // Ungroup node a, which should leave child group empty
    val updatedGraph = graphWithChild.ungroupSelection(ElementIds.from(a))
    
    // Verify the empty child group was automatically removed
    assert(!updatedGraph.groups.contains(childGroupId), "Empty child group should be automatically removed")
    assertEquals(updatedGraph.memberships, Map[GroupMemberId, GroupId](
      a -> parentGroupId,
      b -> parentGroupId
    ), "Node a should be moved to parent group and child group should be removed")
  }

  test("moveToNewGroup should create nested structure when moving nodes within a group") {
    // Create a group with one node, then move that node to a new group
    val graph = ViewerGraph(ViewerGraphElements(nodes = VectorMap(nodeWithId(a), nodeWithId(b))))
    val graphWithGroup = graph.moveToNewGroup("Original Group", a)
    val originalGroupId = graphWithGroup.membership(a).get
    
    // Move node to a new group, which should create a nested structure
    val updatedGraph = graphWithGroup.moveToNewGroup("New Group", a)
    val newGroupId = updatedGraph.membership(a).get
    
    // Verify the new group is nested within the original group
    assertEquals(updatedGraph.memberships(newGroupId), originalGroupId, "New group should be nested in original group")
    assertEquals(updatedGraph.memberships(a), newGroupId, "Node should be in the new group")
    
    // Verify original group now contains the new group instead of the node
    assertEquals(updatedGraph.getDirectChildren(Set(originalGroupId)), Set[GroupMemberId](newGroupId), "Original group should contain the new group")
  }

  test("moveToNewGroup should clean up empty groups when moving all nodes from different groups") {
    // Create two separate groups with one node each
    val graph = ViewerGraph(ViewerGraphElements(nodes = VectorMap(nodeWithId(a), nodeWithId(b), nodeWithId(c))))
    val graphWithGroupA = graph.moveToNewGroup("Group A", a)
    val groupAId = graphWithGroupA.membership(a).get
    
    val graphWithGroupB = graphWithGroupA.moveToNewGroup("Group B", b) 
    val groupBId = graphWithGroupB.membership(b).get
    
    // Move both nodes to a new group at root level, which should leave original groups empty
    val updatedGraph = graphWithGroupB.moveToNewGroup("Combined Group", a, b)
    val combinedGroupId = updatedGraph.membership(a).get
    
    // Verify original groups were removed since they became empty
    assert(!updatedGraph.groups.contains(groupAId), "Group A should be removed after moving its only node")
    assert(!updatedGraph.groups.contains(groupBId), "Group B should be removed after moving its only node") 
    
    // Verify new group exists at root level and contains both nodes
    assert(updatedGraph.groups.contains(combinedGroupId), "Combined group should exist")
    assertEquals(updatedGraph.membership(combinedGroupId), None, "Combined group should be at root level")
    assertEquals(updatedGraph.memberships(a), combinedGroupId, "Node a should be in combined group")
    assertEquals(updatedGraph.memberships(b), combinedGroupId, "Node b should be in combined group")
  }
