package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.ownership.ManualOwner
import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.models.{ElementId, GroupId, NodeId}
import org.jpablo.graphexplorer.viewer.utils.{TestHelpers, Waits}

import scala.concurrent.ExecutionContext.Implicits.global

/** The group command, for a selection that holds a cluster and a node of that
  * cluster.
  *
  * The test does what a person does: a click on the cluster, a shift-click on
  * the node, the key "g" (`selection.group()`), and then the dialog, which
  * calls `createGroupWithLabel`. The command goes to `moveToNewGroup` through
  * `SourceCommand.Group`. The test reads the graph of the viewer and the text
  * that the viewer writes.
  */
class GroupWithOwnMemberSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "GroupWithOwnMemberSpec"

  override def munitFixtures = List(mockStorageFixture())

  private val dot =
    """digraph G {
      |  subgraph cluster_g { n; m }
      |  subgraph cluster_keep { a }
      |}""".stripMargin

  // `GroupId.fromDot` removes the `cluster_` prefix.
  private val g    = GroupId("g")
  private val keep = GroupId("keep")
  private val n    = NodeId("n")
  private val m    = NodeId("m")

  // The node `n` moves with its cluster `g`, and stays in `g`. Only `g` goes
  // into the new group.
  // Failure (before the fix of `moveToNewGroup`): `n` and `g` both go into the
  // new group, and the new group goes into `g`. That is a cycle, and the DOT
  // writer then wrote no part of it.
  test("a group of a cluster and a node in it puts only the cluster into the new group"):
    val owner = ManualOwner()
    withGraphvizAsync: graphviz =>
      val state = ViewerState(ViewTarget.diagram("group-with-own-member"), graphviz, initialSource = Some(dot))(using owner)
      Waits
        .settle(state.fullGraphNow().groupIds.contains(g))
        .flatMap: _ =>
          val before = state.fullGraphNow()
          check(before.groupIds == Set(g, keep), s"precondition: the graph has the groups ${before.groupIds}")
          check(before.membership(n).contains(g), s"precondition: the node n is not in g: ${before.memberships}")
          state.selection.updateSelectionStatus(g)(shiftKey = false)
          state.selection.updateSelectionStatus(n)(shiftKey = true)
          check(state.selection.now().ids.toSet[ElementId] == Set[ElementId](g, n), s"precondition: the selection is not g and n: ${state.selection.now()}")
          state.selection.group()
          val pending = state.pendingNewGroupV.now().map(_.elementIds)
          check(pending.isDefined, "precondition: the group command does not ask for a label")
          state.createGroupWithLabel(pending.get, "Outer")
          Waits.settle(state.fullGraphNow().groupIds.size == 3).map(_ => before)
        .flatMap: before =>
          val after    = state.fullGraphNow()
          val newGroup = after.groupIds -- before.groupIds
          check(newGroup.size == 1, s"the group command did not add one group: ${after.groupIds}")
          val outer = newGroup.head
          check(after.membership(g).contains(outer), s"the cluster g is not in the new group $outer: ${after.memberships}")
          check(after.membership(n).contains(g), s"the node n left its cluster g: ${after.memberships}")
          check(after.membership(outer).isEmpty, s"the new group $outer has the parent ${after.membership(outer)}")
          // The text that the viewer writes must give the same structure.
          val text = state.sourceText.now()
          state.languages
            .forFormat(DiagramFormat.DOT)
            .textToGraph(text)
            .map: reparsed =>
              check(Set(n, m).subsetOf(reparsed.nodeIds), s"the text lost the node n or m:\n$text")
              check(reparsed.membership(n).contains(g), s"in the text, the node n is not in g:\n$text")
              check(reparsed.membership(g).isDefined, s"in the text, the cluster g is not in the new group:\n$text")
        .transform: outcome =>
          owner.killSubscriptions()
          outcome

end GroupWithOwnMemberSpec
