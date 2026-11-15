package org.jpablo.graphexplorer.viewer.desktop

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.jpablo.graphexplorer.board.{BoardCandidate, BoardFileView, FileBoardStore}
import org.jpablo.graphexplorer.gxcore.model.{Board, BoardItem, DiagramMetadata, LibraryEntry}
import org.jpablo.graphexplorer.library.{DesktopLibrary, FakeDesktopShell, Library, LoggedChecks}
import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.graphexplorer.viewer.models.DiagramId
import org.jpablo.graphexplorer.viewer.state.{DocumentSessionId, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** A board of the desktop app: a `.gxboard` file (ADR 0003).
  *
  * The shell here is the fake shell. Its `save_document` compares the base
  * revision with the file, as the real shell does. A test sends each document
  * event through `DesktopBridge`, as the real event goes.
  */
class BoardFileSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "BoardFileSpec"

  override def munitFixtures = List(mockStorageFixture())

  private lazy val sharedGraphviz: Future[Graphviz] = Graphviz.build()

  private var shell     = FakeDesktopShell()
  private var startHref = ""

  override def beforeEach(context: BeforeEach): Unit =
    startHref = dom.window.location.href
    DesktopDocumentRegistry.reset()
    DesktopBridge.reset()
    DesktopOpenRequests.reset()
    shell = FakeDesktopShell()
    shell.install()
    shell.files("/w/arch.dot") = "digraph G {\n  label=\"Architecture\"\n  a -> b\n}"
    shell.files("/w/boards/flow.mmd") = "flowchart LR\n  A[Start] --> B[End]\n"
    shell.files(boardPath) = Board.render(board)

  override def afterEach(context: AfterEach): Unit =
    Library.restoreDefault()
    shell.uninstall()
    DesktopDocumentRegistry.reset()
    DesktopBridge.reset()
    DesktopOpenRequests.reset()
    dom.window.history.replaceState(null, "", startHref)

  private val boardPath = "/w/boards/plan.gxboard"

  // Two files that exist, one file that does not, and one location that is not a file.
  private val board = Board(
    Vector(
      BoardItem("arch", "../arch.dot", 0, 0),
      BoardItem("flow", "flow.mmd", 400, 0),
      BoardItem("gone", "gone.dot", 800, 0),
      BoardItem("schema", "postgres://db.internal/app", 1200, 0, extractor = Some("sql-schema"))
    )
  )

  private def after(ms: Int): Future[Unit] =
    val p = Promise[Unit]()
    js.timers.setTimeout(ms.toDouble)(p.success(()))
    p.future

  /** Wait until `condition` is true, for `ms` at most. */
  private def until(ms: Int)(condition: => Boolean): Future[Boolean] =
    if condition then Future.successful(true)
    else if ms <= 0 then Future.successful(false)
    else after(10).flatMap(_ => until(ms - 10)(condition))

  /** The value of `future`, or `None` when it does not come in `ms`. */
  private def within[A](ms: Int)(future: Future[A]): Future[Option[A]] =
    Future.firstCompletedOf(List(future.map(Some(_)), after(ms).map(_ => None)))

  /** The session of the board file, as the shell makes it when it opens the file. */
  private def openBoard(): DesktopDocumentRegistry.Session =
    DesktopDocumentRegistry.record(boardPath, shell.hash(shell.files(boardPath)), shell.files(boardPath))

  private def fileBoard: Board = Board.parse(shell.files(boardPath)).fold(message => fail(message), identity)

  /** A different tool writes the board file, and the watch loop reports it. */
  private def writeOutside(text: String): Unit =
    shell.files(boardPath) = text
    DesktopBridge.routeDocumentChange(DesktopBridge.DesktopMessage(text, Some(boardPath), Some(shell.hash(text))))

  private def pathOf(target: Option[ViewTarget]): Option[String] =
    target.collect { case ViewTarget.File(session) => DesktopDocumentRegistry.get(session).map(_.path) }.flatten

  // --------------------------------------------------------------- the store

  test("the store reads the board from the board file"):
    val store = FileBoardStore(openBoard().id, None)
    store.load().map(loaded => same(loaded, Right(board)))

  test("a board file that does not parse gives its cause"):
    shell.files(boardPath) = """{ "version": 7, "items": [] }"""
    val store = FileBoardStore(openBoard().id, None)
    store.load().map(loaded => check(loaded.left.exists(_.contains("version 7")), s"no cause: $loaded"))

  test("the location of an item is relative to the board file, and the item opens that file"):
    val store = FileBoardStore(openBoard().id, None)
    for
      arch <- store.target(board.items(0))
      flow <- store.target(board.items(1))
    yield
      same(pathOf(arch), Some("/w/arch.dot"))
      same(pathOf(flow), Some("/w/boards/flow.mmd"))
      check(shell.watched.contains("/w/arch.dot"), "the shell does not watch the file of the item")

  test("an item whose file is missing has no target, and an item that is not a file has none"):
    val store = FileBoardStore(openBoard().id, None)
    for
      gone   <- store.target(board.items(2))
      schema <- store.target(board.items(3))
    yield
      same(gone, None)
      same(schema, None)

  test("a save writes the board file, and a second save has the new base"):
    val store = FileBoardStore(openBoard().id, None)
    val first = board.move("arch", 50, 60)
    val next  = first.move("flow", 70, 80)
    for
      one <- store.save(first)
      _ = same(one, None, "the first save was refused")
      _ = same(fileBoard, first)
      two <- store.save(next)
    yield
      same(two, None, "the second save was refused: the base did not move")
      same(fileBoard, next)
      same(shell.callsOf("save_document").size, 2)

  test("a save of the board that the file has writes nothing"):
    val store = FileBoardStore(openBoard().id, None)
    store.save(board).map: refusal =>
      same(refusal, None)
      same(shell.callsOf("save_document").size, 0)

  test("two saves with no wait write in their order, and the file has the last board"):
    val store = FileBoardStore(openBoard().id, None)
    val first = board.move("arch", 1, 1)
    val last  = board.move("arch", 2, 2)
    val one   = store.save(first)
    val two   = store.save(last)
    for
      a <- one
      b <- two
    yield
      same((a, b), (None, None))
      same(fileBoard, last)

  test("a save does not write over a version of the file that the page does not have"):
    val store  = FileBoardStore(openBoard().id, None)
    val theirs = Board.render(board.remove("gone"))
    // A different tool writes the file, and the page did not get the event yet.
    shell.files(boardPath) = theirs
    store.save(board.move("arch", 5, 5)).map: refusal =>
      check(refusal.exists(_.contains("changed on disk")), s"no refusal about the file: $refusal")
      same(shell.files(boardPath), theirs, "the save wrote over the change of the different tool")

  test("a version of the file from a different tool comes as a change, and a save of this page does not"):
    shell.saveSendsEvent = true
    DesktopBridge.install()
    val owner  = ManualOwner()
    val store  = FileBoardStore(openBoard().id, None)
    val seen   = mutable.Buffer.empty[Either[String, Board]]
    store.changes.foreach(seen += _)(using owner)
    val theirs = board.remove("gone")
    store
      .save(board.move("arch", 5, 5))
      .flatMap: refusal =>
        same(refusal, None)
        same(seen.toList, Nil, "the event of a save of this page came as a change from outside")
        writeOutside(Board.render(theirs))
        afterMicrotasks(())
      .flatMap: _ =>
        same(seen.toList, List(Right(theirs)))
        writeOutside("not a board")
        afterMicrotasks(())
      .map: _ =>
        check(seen.last.isLeft, s"a file that does not parse did not come as a cause: ${seen.last}")
      .transform: outcome =>
        owner.killSubscriptions()
        outcome

  // ------------------------------------------------------------- the library

  /** The library with an entry for each of `paths`. */
  private def libraryWith(paths: String*): Future[DesktopLibrary] =
    paths.foreach(path => shell.putEntry(LibraryEntry(path, DiagramMetadata(), createdAt = 1, updatedAt = 1)))
    DesktopLibrary.load().map: library =>
      Library.install(library)
      library

  test("the diagrams of the library are the candidates, with a location relative to the board file"):
    libraryWith("/w/arch.dot", "/w/boards/flow.mmd", boardPath).flatMap: library =>
      library.readFiles().flatMap: _ =>
        FileBoardStore(openBoard().id, Some(library)).candidates().map: candidates =>
          // A board file is not a diagram, so it is not a candidate.
          same(
            candidates.toSet,
            Set(BoardCandidate("Architecture", "../arch.dot"), BoardCandidate("flow", "flow.mmd"))
          )

  test("a board file in the library is in the list of the boards, and not in the list of the diagrams"):
    libraryWith("/w/arch.dot", boardPath).flatMap: library =>
      library.readFiles().map: _ =>
        val key = shell.keyOf(boardPath)
        same(library.boardsNow().map(b => (b.id, b.name)), List((key, "plan")))
        same(library.indexNow().diagrams.map(_.id), List(DiagramId(shell.keyOf("/w/arch.dot"))))
        same(library.boardItemCount(key), Some(4))

  test("New board makes a board file with no items and an entry, and gives its route"):
    libraryWith().flatMap: library =>
      library.newBoard("Board").map: result =>
        val path = s"${shell.folder}/Board.gxboard"
        same(shell.files.get(path), Some(Board.render(Board.empty)))
        check(shell.entryOf(path).isDefined, "the board file has no entry")
        val session = DesktopDocumentRegistry.find(path).getOrElse(fail("the board file has no session"))
        same(result, Right(Route.File(session.id.value)))
        same(library.boardsNow().map(_.name), List("Board"))

  test("a second new board gets the next name"):
    libraryWith().flatMap: library =>
      for
        _ <- library.newBoard("Board")
        _ <- library.newBoard("Board")
      yield check(shell.files.contains(s"${shell.folder}/Board 2.gxboard"), s"the files are ${shell.files.keys}")

  // ---------------------------------------------------------------- the page

  /** The page of the desktop: the window listeners of `Viewer.startApp`, and
    * a route signal that shows a board file with `BoardFileView`, the
    * function that `Viewer` calls.
    */
  private final class Page(graphviz: Graphviz):
    val router = Router()
    val errors = mutable.Buffer.empty[String]

    private val bus = EventBus[String]()
    private val sub = bus.events.foreach(errors += _)(using unsafeWindowOwner)

    DesktopBridge.install()
    DesktopOpenRequests.install(router)

    private val box = dom.document.createElement("div")
    dom.document.body.appendChild(box)

    private val root = render(
      box,
      div(
        child <-- router.currentRoute.map:
          case Route.File(sessionId) =>
            DocumentSessionId.parse(sessionId).flatMap(DesktopDocumentRegistry.get) match
              case Some(open) if Board.isBoardPath(open.path) => BoardFileView(open.id, graphviz, router, bus)
              case _                                          => div()
          case _ => div()
      )
    )

    def frames: List[dom.Element] =
      val found = box.querySelectorAll(".board-item")
      (0 until found.length).map(found(_)).toList

    def frame(id: String): dom.Element =
      frames.find(_.getAttribute("data-item-id") == id).getOrElse(fail(s"the board shows no item $id"))

    def hasDrawing(id: String): Boolean = frames.exists(f => f.getAttribute("data-item-id") == id && f.querySelector("svg") != null)

    def text: String = box.textContent

    def scale: Double =
      val transform = box.querySelector(".board-layer").asInstanceOf[dom.HTMLElement].style.transform
      """scale\(([0-9.eE+-]+)\)""".r.findFirstMatchIn(transform).map(_.group(1).toDouble).getOrElse(fail(s"no scale in: $transform"))

    def pointer(target: dom.Element, kind: String, x: Double, y: Double): Unit =
      val init = js.Dynamic.literal(bubbles = true, cancelable = true, button = 0, clientX = x, clientY = y, pointerId = 1)
      target.dispatchEvent(js.Dynamic.newInstance(js.Dynamic.global.PointerEvent)(kind, init).asInstanceOf[dom.Event])
      ()

    def close(): Unit =
      root.unmount()
      sub.kill()
      box.parentNode.removeChild(box)
      ()

  private def withPage(body: Page => Future[Unit]): Future[Unit] =
    sharedGraphviz.flatMap: graphviz =>
      val page = Page(graphviz)
      Future
        .delegate(body(page))
        .transform: outcome =>
          page.close()
          outcome

  test("an open of a board file shows the board, and the shell gets displayed"):
    withPage: page =>
      within(5000)(shell.show(boardPath)).flatMap: answer =>
        same(answer.map(_.status), Some("displayed"), s"the open of the board file: $answer")
        until(10000)(page.hasDrawing("arch") && page.hasDrawing("flow")).map: drawn =>
          check(drawn, s"the items have no drawing: ${page.text.take(300)}")
          same(page.frames.size, 4)
          same(page.frame("arch").querySelector(".board-item-name").textContent, "Architecture")
          check(page.frame("gone").classList.contains("missing"), "the item with no file has no mark")
          check(page.frame("schema").classList.contains("missing"), "the item that is not a file has no mark")
          // The open and the draw wrote nothing.
          same(shell.callsOf("save_document").size, 0)
          same(fileBoard, board)

  test("an open of a board file that does not parse is rejected, and the page shows the cause"):
    shell.files(boardPath) = """{ "version": 7, "items": [] }"""
    withPage: page =>
      within(5000)(shell.show(boardPath)).flatMap: answer =>
        same(answer.map(_.status), Some("rejected"), s"the open of the board file: $answer")
        same(answer.flatMap(_.code), Some("PARSE_FAILED"))
        until(2000)(page.text.contains("version 7")).map: shown =>
          check(shown, s"the page does not show the cause: ${page.text.take(300)}")

  test("a drag of an item writes its new place to the board file"):
    withPage: page =>
      within(5000)(shell.show(boardPath))
        .flatMap(_ => until(10000)(page.hasDrawing("arch") && page.hasDrawing("flow")))
        .flatMap: _ =>
          val bar = page.frame("flow").querySelector(".board-item-title")
          page.pointer(bar, "pointerdown", 300, 300)
          page.pointer(bar, "pointermove", 360, 330)
          page.pointer(bar, "pointerup", 360, 330)
          val x = (400 + 60 / page.scale).round.toDouble
          val y = (0 + 30 / page.scale).round.toDouble
          until(2000)(fileBoard.item("flow").exists(item => item.x == x && item.y == y)).map: written =>
            check(written, s"the board file has ${fileBoard.item("flow")}, and not ($x, $y)")
            // Each other item, and each key of the file, stays.
            same(fileBoard.remove("flow"), board.remove("flow"))
            same(page.errors.toList, Nil)

  test("a change of the board file by a different tool shows on the page"):
    withPage: page =>
      within(5000)(shell.show(boardPath))
        .flatMap(_ => until(10000)(page.frames.size == 4))
        .flatMap: _ =>
          writeOutside(Board.render(board.remove("gone").remove("schema").move("arch", 123, 45)))
          until(2000)(page.frames.size == 2)
        .map: shown =>
          check(shown, s"the page still has ${page.frames.size} frames")
          val style = page.frame("arch").asInstanceOf[dom.HTMLElement].style
          same((style.left, style.top), ("123px", "45px"))
          same(shell.callsOf("save_document").size, 0, "the page wrote the board file after a change from outside")
