package org.jpablo.graphexplorer.viewer.desktop

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.render
import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.{DiagramLayout, NodePlace}
import org.jpablo.graphexplorer.library.{FakeDesktopShell, LoggedChecks}
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.components.svgCanvas.{CanvasContainer, LayoutTransition}
import org.jpablo.graphexplorer.viewer.components.{Commands, RouterCommands}
import org.jpablo.graphexplorer.viewer.models.NodeId
import org.jpablo.graphexplorer.viewer.state.{Persistence, SaveResult, ViewTarget, ViewerState}
import org.jpablo.graphexplorer.viewer.utils.{TestHelpers, Waits}
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** The layout of a diagram with a file is a side file next to the file (ADR 0004).
  *
  * The view reads the side file when it opens the file (`read_layout`). The view holds its first drawing until the
  * read ends, so the first drawing shows the stored positions. A read that finds no side file, a failed read, and a
  * shell that does not reply do not stop the drawing. A change of
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
    val render  = state.renderState.observe(using state.owner)
    /** The places of the nodes in each drawing of the view, from the open on, in the order of the drawings. */
    val drawnPlaces = scala.collection.mutable.ArrayBuffer.empty[Map[String, (Double, Double)]]
    state.drawing.foreach(_.foreach(svg => drawnPlaces += LayoutFileSpec.nodePlaces(svg.ref)))(using state.owner)
    def layout: DiagramLayout = state.layout.now()
    def reads                 = shell.callsOf("read_layout")
    def writes                = shell.callsOf("write_layout")

  /** Open the file `path` in a view, with `layoutOnDisk` as its side file, and run `body` at once. `setUp` changes the
    * fake shell before the open. `install = false` opens the file with no shell.
    */
  private def openFile(layoutOnDisk: Option[String], setUp: FakeDesktopShell => Unit = _ => (), install: Boolean = true)(
      body: View => Future[Unit]
  ): Future[Unit] =
    val shell = FakeDesktopShell()
    shell.files(path) = dot
    layoutOnDisk.foreach(text => shell.layouts(path) = text)
    shell.watched += path
    setUp(shell)
    if install then shell.install()
    val owner = ManualOwner()
    withGraphvizAsync: graphviz =>
      val open = DesktopDocumentRegistry.record(path, shell.hash(dot), dot)
      body(View(ViewerState(ViewTarget.File(open.id), graphviz)(using owner), shell))
    .transform: outcome =>
      owner.killSubscriptions()
      shell.uninstall()
      outcome

  /** Open the file as [[openFile]] does, and run `body` when the view read the side file and shows a drawing. */
  private def withFile(layoutOnDisk: Option[String], setUp: FakeDesktopShell => Unit = _ => ())(
      body: View => Future[Unit]
  ): Future[Unit] =
    openFile(layoutOnDisk, setUp): view =>
      Waits
        .settle(view.reads.nonEmpty && view.state.fullGraphNow().nodeIds.nonEmpty && view.drawing.now().nonEmpty, 10000)
        .flatMap(_ => Waits.after(50))
        .flatMap: _ =>
          check(view.reads.nonEmpty, s"the view did not read the side file: ${view.shell.commands}")
          same(view.reads.head.diagramPath.asInstanceOf[String], path, "read_layout names the diagram")
          check(view.drawing.now().nonEmpty, s"the view shows no drawing: ${view.render.now()}")
          body(view)

  /** The difference b - a of the drawn places of the nodes a and b in each drawing that shows the two nodes. */
  private def offsets(view: View): Vector[(Double, Double)] =
    view.drawnPlaces.toVector.collect:
      case places if places.contains("a") && places.contains("b") =>
        (places("b")._1 - places("a")._1, places("b")._2 - places("a")._2)

  /** True when the offset b - a is the offset of [[stored]]: b is 100 points right of a and 50 points above a. An SVG
    * has y down.
    */
  private def showsStored(offset: (Double, Double)): Boolean =
    math.abs(offset._1 - 100) < 1 && math.abs(offset._2 + 50) < 1

  test("the open of a file reads its side file, and the positions apply with no edit"):
    withFile(Some(DiagramLayout.render(stored))): view =>
      for _ <- Waits.settle(view.layout == stored, 5000)
      yield
        same(view.layout, stored, "the layout of the side file")
        same(view.state.documentIsDirty, false, "the load of the side file made the view dirty")
        check(!view.state.history.canUndoNow, "the load of the side file is a step of the history")

  // Found on 2026-11-28 on the desktop. The view read the side file after the open. The first drawing then had the
  // automatic layout of Graphviz, and the nodes moved to their stored positions when the read ended.
  test("the open of a file with a side file draws the stored positions at once, with no automatic layout first"):
    withFile(Some(DiagramLayout.render(stored))): view =>
      for _ <- Waits.settle(view.layout == stored, 5000).flatMap(_ => Waits.after(100))
      yield
        same(view.layout, stored, "precondition: the layout of the side file")
        val withBoth = view.drawnPlaces.toVector.filter(p => p.contains("a") && p.contains("b"))
        check(withBoth.nonEmpty, s"precondition: no drawing shows the nodes a and b: ${view.drawnPlaces}")
        // The side file puts b 100 points right of a, and 50 points above a. An SVG has y down.
        withBoth.zipWithIndex.foreach: (places, i) =>
          val (ax, ay) = places("a")
          val (bx, by) = places("b")
          check(
            math.abs((bx - ax) - 100) < 1 && math.abs((by - ay) + 50) < 1,
            f"drawing ${i + 1} of ${withBoth.size} does not show the stored positions: b - a = (${bx - ax}%.1f, ${by - ay}%.1f), " +
              s"and the side file gives (100, -50). All drawings: $withBoth"
          )

  test("the view holds its first drawing while the read of the side file has no reply"):
    val reply = Promise[Unit]()
    openFile(Some(DiagramLayout.render(stored)), _.layoutReplyAfter = Some(reply.future)): view =>
      for
        _ <- Waits.settle(view.reads.nonEmpty && view.state.fullGraphNow().nodeIds.nonEmpty, 10000)
        // The time for a render, if the view does not hold it.
        _ <- Waits.after(300)
        _ = check(view.state.fullGraphNow().nodeIds.nonEmpty, "precondition: the view did not parse the source")
        _ = same(view.drawnPlaces.toVector, Vector.empty, "the view drew before the read of the side file ended")
        _ = reply.success(())
        _ <- Waits.settle(view.drawing.now().nonEmpty, 5000).flatMap(_ => Waits.after(100))
      yield
        same(view.layout, stored, "the layout of the side file")
        check(offsets(view).nonEmpty, s"no drawing shows the nodes a and b after the read: ${view.drawnPlaces}")
        check(offsets(view).forall(showsStored), s"a drawing does not show the stored positions: ${offsets(view)}")
        same(view.state.documentIsDirty, false, "the load of the side file made the view dirty")
        check(!view.state.history.canUndoNow, "the load of the side file is a step of the history")

  test("a file with no side file draws its automatic layout after the read"):
    withFile(None): view =>
      same(view.layout, DiagramLayout.empty, "the layout of a file with no side file")
      check(offsets(view).nonEmpty, s"no drawing shows the nodes a and b: ${view.drawnPlaces}")
      same(view.state.documentIsDirty, false, "the view is dirty")
      Future.unit

  test("a failed read of the side file does not stop the drawing"):
    withFile(Some(DiagramLayout.render(stored)), _.refusals("read_layout") = ("IO_ERROR", "the disk does not answer")): view =>
      same(view.layout, DiagramLayout.empty, "a failed read gave a layout")
      check(offsets(view).nonEmpty, s"no drawing shows the nodes a and b: ${view.drawnPlaces}")
      same(view.state.documentIsDirty, false, "the view is dirty")
      Future.unit

  test("a side file that does not parse does not stop the drawing"):
    withFile(Some("{ this is not a layout")): view =>
      same(view.layout, DiagramLayout.empty, "an unreadable side file gave a layout")
      check(offsets(view).nonEmpty, s"no drawing shows the nodes a and b: ${view.drawnPlaces}")
      same(view.state.documentIsDirty, false, "the view is dirty")
      Future.unit

  test("a file with no shell draws at once, and reads no side file"):
    openFile(Some(DiagramLayout.render(stored)), install = false): view =>
      for _ <- Waits.settle(view.drawing.now().nonEmpty, 10000)
      yield
        check(view.drawing.now().nonEmpty, s"the view shows no drawing: ${view.render.now()}")
        same(view.reads.size, 0, "a view with no shell read the side file")
        same(view.layout, DiagramLayout.empty, "a view with no shell has a layout")

  test("a shell that does not reply to the read of the side file does not stop the drawing, and the late read applies"):
    val reply = Promise[Unit]()
    openFile(Some(DiagramLayout.render(stored)), _.layoutReplyAfter = Some(reply.future)): view =>
      val start = js.Date.now()
      for
        _ <- Waits.settle(view.drawing.now().nonEmpty, Persistence.LayoutReadPatienceMs + 5000)
        _ = check(view.drawing.now().nonEmpty, s"the view shows no drawing: ${view.render.now()}")
        waited = js.Date.now() - start
        _ = check(waited >= Persistence.LayoutReadPatienceMs - 50, s"the view drew after $waited ms, before the limit")
        _ = same(view.layout, DiagramLayout.empty, "precondition: the view has no layout before the reply")
        _ = reply.success(())
        _ <- Waits.settle(view.layout == stored, 5000)
      yield
        same(view.layout, stored, "the late read of the side file did not apply")
        same(view.state.documentIsDirty, false, "the late load of the side file made the view dirty")
        check(!view.state.history.canUndoNow, "the late load of the side file is a step of the history")

  test("a change of the layout before the read of the side file ends wins over the read"):
    val reply = Promise[Unit]()
    openFile(Some(DiagramLayout.render(stored)), _.layoutReplyAfter = Some(reply.future)): view =>
      for
        _ <- Waits.settle(view.reads.nonEmpty && view.state.fullGraphNow().nodeIds.nonEmpty, 10000)
        _ = view.state.history.markGesture()
        _ = view.state.freezeLayout()
        frozen = view.layout
        _ = check(frozen.isManual && frozen != stored, s"precondition: Freeze did not set the manual mode: $frozen")
        _ = reply.success(())
        _ <- Waits.settle(view.drawing.now().nonEmpty, 5000).flatMap(_ => Waits.after(100))
      yield
        same(view.layout, frozen, "the read of the side file replaced the change of the person")
        same(view.state.documentIsDirty, true, "the view is clean, but its layout is not the side file")

  test("the canvas shows the first drawing of a file with a side file with no layout transition"):
    val shell = FakeDesktopShell()
    shell.files(path) = dot
    shell.layouts(path) = DiagramLayout.render(stored)
    shell.watched += path
    shell.install()
    val owner = ManualOwner()
    withGraphvizAsync: graphviz =>
      val open     = DesktopDocumentRegistry.record(path, shell.hash(dot), dot)
      val state    = ViewerState(ViewTarget.File(open.id), graphviz)(using owner)
      val commands = Commands(state, RouterCommands(Router()))
      val box      = dom.document.createElement("div").asInstanceOf[dom.html.Element]
      box.style.width = "800px"
      box.style.height = "600px"
      dom.document.body.appendChild(box)
      val shown       = scala.collection.mutable.ArrayBuffer.empty[dom.svg.SVG]
      var transitions = 0
      def record(): Unit =
        Option(box.querySelector("#canvas-container > svg")).collect { case svg: dom.svg.SVG => svg }.foreach: svg =>
          if !shown.exists(_ eq svg) then shown += svg
        if box.querySelectorAll(s"svg.${LayoutTransition.transitioningClass}").length > 0 then transitions += 1
      val observer = new dom.MutationObserver((records, _) =>
        records.foreach: r =>
          if Option(r.oldValue).exists(_.contains(LayoutTransition.transitioningClass)) then transitions += 1
        record()
      )
      observer.observe(
        box,
        new dom.MutationObserverInit:
          childList = true
          subtree = true
          attributes = true
          attributeOldValue = true
          attributeFilter = js.Array("class")
      )
      val root = render(box, CanvasContainer(state, commands))
      record()
      Waits
        .settle(shown.nonEmpty && state.layout.now() == stored, 10000)
        // The transition lasts 300 ms.
        .flatMap(_ => Waits.after(600))
        .map: _ =>
          record()
          check(shown.nonEmpty, s"the canvas shows no drawing: ${state.renderState.observe(using state.owner).now()}")
          same(state.layout.now(), stored, "precondition: the layout of the side file")
          same(transitions, 0, "the canvas animated the first drawing of the file")
          same(shown.size, 1, s"the canvas showed ${shown.size} drawings, and the first drawing must be the last")
          val places = LayoutFileSpec.nodePlaces(shown.head)
          check(places.contains("a") && places.contains("b"), s"the drawing does not show a and b: $places")
          val offset = (places("b")._1 - places("a")._1, places("b")._2 - places("a")._2)
          check(showsStored(offset), s"the canvas does not show the stored positions: b - a = $offset")
        .transform: outcome =>
          observer.disconnect()
          state.mouseAction.inactive()
          root.unmount()
          box.parentNode.removeChild(box)
          owner.killSubscriptions()
          shell.uninstall()
          outcome

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

object LayoutFileSpec:

  /** The center of the text of each Graphviz node of `svg`, by the name of the node (the `title` of its group), in
    * the user units of the drawing.
    */
  def nodePlaces(svg: org.scalajs.dom.svg.SVG): Map[String, (Double, Double)] =
    svg
      .querySelectorAll("g.node")
      .toSeq
      .flatMap: g =>
        for
          title <- Option(g.querySelector("title")).map(_.textContent.trim)
          text  <- Option(g.querySelector("text"))
          x     <- Option(text.getAttribute("x")).flatMap(_.toDoubleOption)
          y     <- Option(text.getAttribute("y")).flatMap(_.toDoubleOption)
        yield title -> (x, y)
      .toMap
