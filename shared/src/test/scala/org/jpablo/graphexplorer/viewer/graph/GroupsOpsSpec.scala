package org.jpablo.graphexplorer.viewer.graph

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.models.ViewerGroup.group
import org.jpablo.graphexplorer.viewer.models.ViewerNode.node
import org.jpablo.graphexplorer.viewer.models.{AttributeId, Attributes, GroupId, NodeId, ViewerGroup}

class GroupsOpsSpec extends FunSuite:

  val rootId = ViewerGraphElements.defaultRootId
  val rootGroup = ViewerGroup(rootId)

  val g = rootId
  val initialGroup = group(g)

  val a = NodeId("a")
  val b = NodeId("b")
  val c = NodeId("c")

  test("moveToNewGroup should create a new group and add elements to it") {
    val graph = ViewerGraph(ViewerGraphElements(nodes = Map(node(a), node(b), node(c))))
    // sanity check
    assert(graph.groups == Map(initialGroup))
    assert(graph.memberships.isEmpty)

    // Add elements to a new group with a label
    val updatedGraph = graph.moveToNewGroup("New Group", a, b)

    val newGroupId = updatedGraph.membership(a).get
    val newGroup =
      newGroupId -> ViewerGroup(newGroupId, Attributes(Map(AttributeId("label") -> AttrValue("New Group"))))

    val expected =
      ViewerGraph.minimal.modifyElements.using(
        _.copy(
          nodes       = Map(node(a), node(b), node(c)),
          memberships = Map(a -> newGroupId, b -> newGroupId),
          groups      = Map(initialGroup, newGroup)
        )
      )
    assertEquals(updatedGraph, expected, "The graph should be updated with the new group and memberships")
  }

  test("moveToNewGroup should add the new group to a common parent when elements share a parent") {
    val graph =
      ViewerGraph(ViewerGraphElements(nodes = Map(node(a), node(b), node(c))))
        .moveToNewGroup("Parent group", a, b)


    val parentGroup = graph.membership(a).get

    // Add elements to a new group
    val updatedGraph = graph.moveToNewGroup("Nested Group", a, b)

    // Verify the new group was created
    val newGroup = updatedGraph.membership(a).get
    assertEquals(updatedGraph.memberships(b), newGroup, "Both nodes should be in the same group")

    // Verify the new group is a child of group1
    assertEquals(updatedGraph.memberships(newGroup), parentGroup, "New group should be a child of group1")
  }
