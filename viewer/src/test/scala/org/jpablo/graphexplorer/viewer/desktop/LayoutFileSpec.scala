package org.jpablo.graphexplorer.viewer.desktop

import com.raquo.airstream.ownership.ManualOwner
import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.{DiagramLayout, NodePlace}
import org.jpablo.graphexplorer.library.{FakeDesktopShell, LoggedChecks}
import org.jpablo.graphexplorer.viewer.models.NodeId
import org.jpablo.graphexplorer.viewer.state.{SaveResult, ViewTarget, ViewerState}
import org.jpablo.graphexplorer.viewer.utils.{TestHelpers, Waits}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** The layout of a diagram with a file is a side file next to the file (ADR 0004).
  *
  * The view reads the side file when it opens the file (`read_layout`). A change of
  * the layout makes the view dirty, as an edit of the source does. A save writes
  * the side file with a compare-and-swap (`write_layout`). Arrange and a save
  * remove the side file. The source file never gets the positions.
  */
class LayoutFileSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "LayoutFileSpec"

  override def check(condition: Boolean, clue: => String): Unit =
    if !condition then throw AssertionError(s"[$checkPrefix] $clue")

  override def munitFixtures = List(mockStorageFixture())

  override def beforeEach(context: BeforeEach): Unit =
    DesktopDocumentRegistry.reset()
    DesktopBridge.reset()

  override def afterEach(context: AfterEach): Unit =
    DesktopDocumentRegistry.reset()
    DesktopBridge.reset()
    js.Dynamic.global.window.__TAURI__ = null

  private val path = "/w/arch.dot"
  private val dot  = "digraph G {\n  a -> b\n  b -> c\n}\n"

  private val stored = DiagramLayout(nodes = Map("a" -> NodePlace(0, 0), "b" -> NodePlace(100, 50)))

  private final class View(val state: ViewerState, val shell: FakeDesktopShell):
    val drawing = state.drawing.observe(using state.owner)
    def layout: DiagramLayout = state.layout.now()
    def reads                 = shell.callsOf("read_layout")
    def writes                = shell.callsOf("write_layout")

  private def withFile(layoutOnDisk: Option[String])(body: View => Future[Unit]): Future[Unit] =
    val shell = FakeDesktopShell()
    shell.files(path) = dot
    layoutOnDisk.foreach(text => shell.layouts(path) = text)
    shell.watched += path
    shell.install()
    val owner = ManualOwner()
    withGraphvizAsync: graphviz =>
      val open  = DesktopDocumentRegistry.record(path, shell.hash(dot), dot)
      val state = ViewerState(ViewTarget.File(open.id), graphviz)(using owner)
      val view  = View(state, shell)
      Waits
        .settle(view.reads.nonEmpty && state.fullGraphNow().nodeIds.nonEmpty && view.drawing.now().nonEmpty, 10000)
        .flatMap(_ => Waits.after(50))
        .flatMap: _ =>
          check(view.reads.nonEmpty, s"the view did not read the side file: ${shell.commands}")
          same(view.reads.head.diagramPath.asInstanceOf[String], path, "read_layout names the diagram")
          body(view)
    .transform: outcome =>
      owner.killSubscriptions()
      shell.uninstall()
      outcome

  test("the open of a file reads its side file, and the positions apply with no edit"):
    withFile(Some(DiagramLayout.render(stored))): view =>
      for _ <- Waits.settle(view.layout == stored, 5000)
      yield
        same(view.layout, stored, "the layout of the side file")
        same(view.state.documentIsDirty, false, "the load of the side file made the view dirty")
        check(!view.state.history.canUndoNow, "the load of the side file is a step of the history")

  test("Freeze makes the file dirty, and Save writes the side file and not the positions into the source"):
    withFile(None): view =>
      same(view.state.documentIsDirty, false, "precondition: the view is clean")
      view.state.history.markGesture()
      view.state.freezeLayout()
      check(view.layout.hasPositions, "precondition: Freeze stored positions")
      same(view.state.documentIsDirty, true, "a change of the layout did not make the view dirty")
      for result <- view.state.save()
      yield
        same(result, SaveResult.Saved, "the save")
        same(view.writes.size, 1, s"write_layout calls: ${view.shell.commands}")
        val write = view.writes.head
        same(write.diagramPath.asInstanceOf[String], path, "write_layout names the diagram")
        check(write.baseRevision == null || js.isUndefined(write.baseRevision), s"a new side file has a base: ${write.baseRevision}")
        val written = DiagramLayout.parse(write.text.asInstanceOf[String]).fold(e => throw AssertionError(e), identity)
        same(written, view.layout, "the text of the side file")
        same(view.shell.layouts.get(path).map(DiagramLayout.parse), Some(Right(view.layout)), "the side file on disk")
        check(!view.shell.files(path).contains("pos="), "the save put the positions into the source file")
        same(view.state.documentIsDirty, false, "the view stays dirty after the save")

  test("Arrange and Save remove the side file, with the revision of the side file as the base"):
    val text = DiagramLayout.render(stored)
    withFile(Some(text)): view =>
      for
        _ <- Waits.settle(view.layout == stored, 5000)
        _ = view.state.history.markGesture()
        _ = view.state.arrangeLayout()
        _ = same(view.state.documentIsDirty, true, "Arrange did not make the view dirty")
        result <- view.state.save()
      yield
        same(result, SaveResult.Saved, "the save")
        same(view.writes.size, 1, s"write_layout calls: ${view.shell.commands}")
        val write = view.writes.head
        check(write.text == null || js.isUndefined(write.text), s"Arrange wrote a side file: ${write.text}")
        same(write.baseRevision.asInstanceOf[String], view.shell.hash(text), "the base of the write")
        check(!view.shell.layouts.contains(path), "the side file stays")
        same(view.state.documentIsDirty, false, "the view stays dirty after the save")

  test("Discard puts back the layout of the side file"):
    withFile(Some(DiagramLayout.render(stored))): view =>
      for _ <- Waits.settle(view.layout == stored, 5000)
      yield
        view.state.history.markGesture()
        view.state.arrangeLayout()
        same(view.state.documentIsDirty, true, "precondition: Arrange made the view dirty")
        view.state.discardEdit()
        same(view.layout, stored, "Discard kept the change of the layout")
        same(view.state.documentIsDirty, false, "the view stays dirty after Discard")

  test("a locked file saves its layout, and not its source"):
    withFile(None): view =>
      view.state.metadata.metadata.update(_.copy(locked = true))
      view.state.history.markGesture()
      view.state.placeNodes(Map(NodeId("a") -> org.jpablo.graphexplorer.gxcore.model.LayoutPoint(1, 2)))
      for result <- view.state.save()
      yield
        same(result, SaveResult.Saved, "the save of the layout of a locked file")
        same(view.shell.callsOf("save_document").size, 0, "the save wrote the source of a locked file")
        same(view.writes.size, 1, s"write_layout calls: ${view.shell.commands}")
