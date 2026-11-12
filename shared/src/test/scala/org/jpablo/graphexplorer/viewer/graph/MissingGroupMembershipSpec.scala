package org.jpablo.graphexplorer.viewer.graph

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.ViewerGroup.groupWithId
import org.jpablo.graphexplorer.viewer.models.ViewerNode.nodeWithId

import scala.collection.immutable.VectorMap

/** The DOT writer (`viewerGraphElementsToText`) and a membership whose parent
  * group is not in `elements.groups`.
  *
  * The writer puts a member in the cluster of its parent group. A parent that
  * does not exist gives no cluster. Then the member must go to the top level
  * of the graph, as an arrow whose group is gone does. Before the fix, nodes
  * and groups did not:
  *  - `clusterNodeIds` held each node with a membership, also when the parent
  *    did not exist. Thus the writer did not write the node.
  *  - `topLevelGroups` holds only the groups with no membership. A group whose
  *    parent did not exist was not top level, and it was not a child of a
  *    written group. Thus the writer did not write the group and its members.
  */
class MissingGroupMembershipSpec extends FunSuite:

  private val gone = GroupId("gone")

  /** The line of the node statement for `id`. A node statement starts with the
    * quoted id, and it has no edge operator.
    */
  private def nodeStatement(text: String, id: NodeId): Option[String] =
    text.linesIterator.map(_.trim).find(line => line.startsWith(s"\"${id.value}\"") && !line.contains("->"))

  private def subgraphStatement(text: String, name: String): Option[String] =
    text.linesIterator.map(_.trim).find(line => line.startsWith("subgraph") && line.contains(name))

  // The node `x` has no arrow. Its membership points to the group `gone`, and
  // the graph has no such group. The node `a` is in the group `keep`.
  // Failure (before the fix): the text had no statement for `x`. The node and
  // its label went away from the diagram.
  test("a node whose group does not exist is written at the top level"):
    val x = NodeId("x")
    val a = NodeId("a")
    val elements = ViewerGraphElements(
      nodes = VectorMap(nodeWithId(x, "label" -> "X"), nodeWithId(a)),
      memberships = Map(x -> gone, a -> GroupId("keep")),
      groups = Map(groupWithId(GroupId("keep")))
    )
    val text = viewerGraphElementsToText(elements)
    assert(nodeStatement(text, a).isDefined, s"precondition: the text has no statement for the node a:\n$text")
    assert(nodeStatement(text, x).isDefined, s"the text has no statement for the node x, whose group does not exist:\n$text")
    // The writer can put the attributes on the lines after the id. Only `x`
    // has the label X.
    assert(text.contains("label=\"X\""), s"the text has no label X for the node x:\n$text")

  // The group `orphan` holds the node `n`. The membership of `orphan` points to
  // the group `gone`, and the graph has no such group. The graph has one more
  // group, `keep`, at the top level, so `topLevelGroups` is not empty. Before
  // the fix, the writer then wrote only the top-level groups. It used all
  // groups only when no group was at the top level.
  // Failure (before the fix): the text had no subgraph for `orphan`, and no
  // statement for `n`.
  test("a group whose parent group does not exist is written at the top level"):
    val n      = NodeId("n")
    val a      = NodeId("a")
    val orphan = GroupId("orphan")
    val keep   = GroupId("keep")
    val elements = ViewerGraphElements(
      nodes = VectorMap(nodeWithId(n), nodeWithId(a)),
      memberships = Map(n -> orphan, orphan -> gone, a -> keep),
      groups = Map(groupWithId(orphan), groupWithId(keep))
    )
    val text = viewerGraphElementsToText(elements)
    assert(subgraphStatement(text, "keep").isDefined, s"precondition: the text has no subgraph for the group keep:\n$text")
    assert(subgraphStatement(text, "orphan").isDefined, s"the text has no subgraph for the group orphan, whose parent does not exist:\n$text")
    assert(nodeStatement(text, n).isDefined, s"the text has no statement for the node n, a member of the group orphan:\n$text")

  // ---------------------------------------------------------------------------
  // A path that a person can take: a group of a group and its own member.
  // ---------------------------------------------------------------------------

  // The group `g` holds the nodes `n` and `m`. A person selects `g` and `n`,
  // and groups them (the "group" command and `SourceCommand.Group` call
  // `moveToNewGroup`). Only `g` goes into the new group. `n` moves with `g`,
  // and stays in `g`.
  // Failure (before the fix of `moveToNewGroup`): `n` and `g` went into the
  // new group `g1`. The one parent of the moved elements was `g` (the parent
  // of `n`), so `g1` went into `g`. That gave a cycle: `g -> g1` and
  // `g1 -> g`. Neither group was top level, and the DOT writer then wrote no
  // part of the cycle: the text had no subgraph for `g`, and no statement for
  // `n` and `m`.
  test("a new group of a group and its own member keeps the members in the text"):
    val n    = NodeId("n")
    val m    = NodeId("m")
    val a    = NodeId("a")
    val g    = GroupId("g")
    val keep = GroupId("keep")
    val graph = ViewerGraph(
      ViewerGraphElements(
        nodes = VectorMap(nodeWithId(n), nodeWithId(m), nodeWithId(a)),
        memberships = Map(n -> g, m -> g, a -> keep),
        groups = Map(groupWithId(g), groupWithId(keep))
      )
    )
    val grouped  = graph.moveToNewGroup(ElementIds.from(g, n))
    val newGroup = grouped.groupIds -- graph.groupIds
    assertEquals(newGroup.size, 1, s"the group did not add one group: ${grouped.groupIds}")
    assertEquals(grouped.membership(g), newGroup.headOption, s"g is not in the new group: ${grouped.memberships}")
    assertEquals(grouped.membership(n), Some(g), s"n left its group g: ${grouped.memberships}")
    assertEquals(grouped.membership(newGroup.head), None, s"the new group is not top level: ${grouped.memberships}")
    val text = viewerGraphElementsToText(grouped.elements)
    assert(subgraphStatement(text, "keep").isDefined, s"precondition: the text has no subgraph for the group keep:\n$text")
    assert(
      subgraphStatement(text, "cluster_g\"").isDefined && nodeStatement(text, n).isDefined && nodeStatement(text, m).isDefined,
      s"after the group of g and its member n, the text lost g, n or m (memberships: ${grouped.memberships}):\n$text"
    )

end MissingGroupMembershipSpec
