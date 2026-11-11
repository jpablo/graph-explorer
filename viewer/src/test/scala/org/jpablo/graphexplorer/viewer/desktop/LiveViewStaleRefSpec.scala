package org.jpablo.graphexplorer.viewer.desktop

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.command.LiveViewCommand
import org.jpablo.graphexplorer.library.{FakeDesktopShell, LoggedChecks}
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Color
import org.jpablo.graphexplorer.viewer.models.{AttributeUpdates, ElementId, NodeId}
import org.jpablo.graphexplorer.viewer.state.{ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers

import scala.concurrent.ExecutionContext.Implicits.global

/** A live-view command puts refs into the selection, and the viewer does not
  * check them against the graph. A stale ref (for example `node:ghost` from a
  * socket client) then reaches a toolbar edit. `updateAttributes` adds a node
  * for an unknown NodeId, so the edit must not get the stale ref.
  */
class LiveViewStaleRefSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "LiveViewStaleRefSpec"

  override def munitFixtures = List(mockStorageFixture())

  private val shell = FakeDesktopShell()

  override def beforeEach(context: BeforeEach): Unit =
    LiveViewCommands.reset()
    DesktopDocumentRegistry.reset()
    shell.install()

  override def afterEach(context: AfterEach): Unit =
    LiveViewCommands.reset()
    DesktopDocumentRegistry.reset()
    shell.uninstall()

  private val path  = "/w/arch.dot"
  private val ghost = NodeId("ghost")

  test("a stale ref from a live-view select, then a toolbar change: the graph gets no phantom node"):
    // Bug: the toolbar edit gives `node:ghost` to `updateAttributes`, and the
    // graph gets a node `ghost` that the diagram did not have.
    withGraphvizAsync: graphviz =>
      val text  = "digraph { a; b }"
      shell.files(path) = text
      val open  = DesktopDocumentRegistry.record(path, "rev-1", text)
      val state = ViewerState(ViewTarget.File(open.id), graphviz)
      LiveViewCommands.attach(state)
      afterMicrotasks:
        val outcome = LiveViewCommands.run(LiveViewCommand.Select(path, Set[ElementId](ghost, NodeId("a"))))
        check(outcome.isRight, s"precondition: the select was refused: $outcome")
        check(state.selection.now().contains(ghost), s"precondition: the selection has the stale ref: ${state.selection.now()}")
        // The toolbar of the selected nodes does this (AttributesToolbar.selectionView).
        state.elementAttributesUpdates(state.selection.now()).set(AttributeUpdates.of(Color -> "blue"))
        val graph = state.fullGraphNow()
        check(!graph.nodeIds.contains(ghost), s"the toolbar edit added the phantom node $ghost: ${graph.nodeIds}")
        val color = graph.nodes.get(NodeId("a")).flatMap(_.attributes.get(Color)).map(_.toString)
        check(color.contains("blue"), s"control: the edit must still apply to node a, but its color is $color")
        check(!state.sourceText.now().contains("ghost"), s"the text has the phantom node:\n${state.sourceText.now()}")
