package org.jpablo.graphexplorer.board

import com.raquo.laminar.api.L.{EventBus, render, unsafeWindowOwner}
import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.{Board, BoardItem}
import org.jpablo.graphexplorer.library.{BrowserBoards, Library}
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.backends.DefaultDiagramLanguages
import org.jpablo.graphexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidQueue
import org.jpablo.graphexplorer.viewer.models.DiagramId
import org.jpablo.graphexplorer.viewer.state.RightPanelSection
import org.jpablo.graphexplorer.viewer.utils.{TestHelpers, TimerHelpers}
import org.scalajs.dom

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** The page of a board in the web app: many diagrams of the browser library
  * on one canvas (ADR 0003).
  *
  * These tests use the real page elements, the real Graphviz, and the real
  * Mermaid. The store is the storage of the browser.
  */
class BoardViewSpec extends FunSuite with TestHelpers with TimerHelpers:

  private lazy val sharedGraphviz: Future[Graphviz] = TestHelpers.sharedGraphviz

  override def munitFixtures = List(mockStorageFixture())

  private val dot     = "digraph G {\n  label=\"Orders\"\n  a -> b\n}"
  private val mermaid = "flowchart LR\n  A[Start] --> B[End]\n"

  // A DOT source that does not parse.
  private val brokenDot = "digraph G {\n  a -> \n"

  /** A mounted board view, and a handle on its page elements. */
  private final class Page(val boardId: String, val root: dom.Element, val errors: mutable.Buffer[String]):
    def frames: List[dom.Element] =
      val found = root.querySelectorAll(".board-item")
      (0 until found.length).map(found(_)).toList

    def frame(itemId: String): dom.Element =
      frames.find(_.getAttribute("data-item-id") == itemId).getOrElse(fail(s"the board shows no item $itemId: ${frames.map(_.getAttribute("data-item-id"))}"))

    def hasDrawing(itemId: String): Boolean = frame(itemId).querySelector(".board-item-body svg") != null

    def titleOf(itemId: String): String = frame(itemId).querySelector(".board-item-name").textContent

    def placeOf(itemId: String): (String, String) =
      val style = frame(itemId).asInstanceOf[dom.HTMLElement].style
      (style.left, style.top)

    def stored: Board = BrowserBoards.read(boardId).fold(message => fail(message), identity)

    /** The scale of the view now, from the transform of the layer of the items. */
    def scale: Double =
      val transform = root.querySelector(".board-layer").asInstanceOf[dom.HTMLElement].style.transform
      """scale\(([0-9.eE+-]+)\)""".r.findFirstMatchIn(transform).map(_.group(1).toDouble).getOrElse(fail(s"no scale in: $transform"))

    def pointer(target: dom.Element, kind: String, x: Double, y: Double): Unit =
      val init = js.Dynamic.literal(bubbles = true, cancelable = true, button = 0, clientX = x, clientY = y, pointerId = 1)
      target.dispatchEvent(js.Dynamic.newInstance(js.Dynamic.global.PointerEvent)(kind, init).asInstanceOf[dom.Event])
      ()

  /** Store `board`, mount its page, and run `body`. The end of the test removes the page. */
  private def onBoard(board: Board)(body: Page => Future[Unit]): Future[Unit] =
    val boardId = BrowserBoards.create("Test board").fold(message => fail(message), identity)
    assertEquals(BrowserBoards.write(boardId, board), None)
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    val errors = mutable.Buffer.empty[String]
    val bus    = EventBus[String]()
    val sub    = bus.events.foreach(errors += _)(using unsafeWindowOwner)
    sharedGraphviz
      .flatMap: graphviz =>
        val store   = BrowserBoardStore(boardId, DefaultDiagramLanguages(graphviz))
        val mounted = render(container, BoardView(store, graphviz, Router(), bus))
        body(Page(boardId, container, errors)).transform: outcome =>
          mounted.unmount()
          outcome
      .transform: outcome =>
        sub.kill()
        dom.document.body.removeChild(container)
        outcome

  private def location(id: DiagramId): String = BrowserBoards.locationOf(id)

  test("a board shows the drawing and the title of each item, at the place of the item"):
    val orders = Library.createDiagram("Untitled", dot)
    val flow   = Library.createDiagram("Login flow", mermaid)
    val board  = Board(Vector(BoardItem("orders", location(orders), 0, 0), BoardItem("flow", location(flow), 400, 120)))
    onBoard(board): page =>
      waitFor(s"two frames with a drawing: ${page.root.innerHTML.take(600)}")(
        page.frames.size == 2 && page.hasDrawing("orders") && page.hasDrawing("flow")
      ).map: _ =>
        // A diagram with no name of its own shows the title of its source.
        assertEquals(page.titleOf("orders"), "Orders")
        assertEquals(page.titleOf("flow"), "Login flow")
        assertEquals(page.placeOf("orders"), ("0px", "0px"))
        assertEquals(page.placeOf("flow"), ("400px", "120px"))
        // Graphviz drew the DOT item, and Mermaid drew the Mermaid item.
        assert(page.frame("orders").querySelector("svg g.graph") != null, page.frame("orders").innerHTML.take(300))
        assert(page.frame("flow").innerHTML.contains("aria-roledescription=\"flowchart"), page.frame("flow").innerHTML.take(300))
        assertEquals(page.errors.toList, Nil)

  test("an item whose diagram is not in the library stays on the board, marked as missing"):
    val orders = Library.createDiagram("Orders", dot)
    val board  = Board(Vector(BoardItem("orders", location(orders), 0, 0), BoardItem("gone", "browser:no-such-diagram", 400, 0)))
    onBoard(board): page =>
      waitFor("the frame of the present item has a drawing")(page.frames.size == 2 && page.hasDrawing("orders")).map: _ =>
        assert(page.frame("gone").classList.contains("missing"), page.frame("gone").getAttribute("class"))
        assert(page.frame("gone").textContent.contains("Missing: browser:no-such-diagram"), page.frame("gone").textContent)
        assert(!page.frame("orders").classList.contains("missing"))
        // The missing item stays in the stored board.
        assertEquals(page.stored, board)

  test("a drag of the title bar moves the item, and the end of the drag stores the place"):
    val orders = Library.createDiagram("Orders", dot)
    val board  = Board(Vector(BoardItem("orders", location(orders), 100, 50)))
    onBoard(board): page =>
      waitFor("the frame has a drawing")(page.frames.size == 1 && page.hasDrawing("orders"))
        .flatMap: _ =>
          val bar = page.frame("orders").querySelector(".board-item-title")
          page.pointer(bar, "pointerdown", 200, 200)
          page.pointer(bar, "pointermove", 260, 230)
          // The place is live in the page, and the store does not have it yet.
          assertEquals(page.stored, board)
          page.pointer(bar, "pointerup", 260, 230)
          afterMicrotasks(())
        .map: _ =>
          // The pointer moved 60 x 30 pixels. The scale of the view gives the units of the board.
          val x = (100 + 60 / page.scale).round.toDouble
          val y = (50 + 30 / page.scale).round.toDouble
          assertEquals(page.stored.item("orders").map(item => (item.x, item.y)), Some((x, y)))
          assertEquals(page.placeOf("orders"), (s"${x.toInt}px", s"${y.toInt}px"))
          assert(page.frame("orders").classList.contains("selected"))

  test("the remove control takes the item from the board, and the diagram stays in the library"):
    val orders = Library.createDiagram("Orders", dot)
    val flow   = Library.createDiagram("Login flow", mermaid)
    val board  = Board(Vector(BoardItem("orders", location(orders), 0, 0), BoardItem("flow", location(flow), 400, 0)))
    onBoard(board): page =>
      waitFor("two frames")(page.frames.size == 2)
        .flatMap: _ =>
          page.frame("flow").querySelector("button[aria-label='Remove from the board']").asInstanceOf[dom.HTMLElement].click()
          waitFor("one frame")(page.frames.size == 1)
        .map: _ =>
          assertEquals(page.stored.items.map(_.id), Vector("orders"))
          assert(Library.diagramExists(flow), "the remove deleted the diagram")

  test("the add menu lists the diagrams of the library, and a click puts the diagram on the board"):
    // The diagram has no name of its own, so the menu shows the title of its source.
    val stock = Library.createDiagram("Untitled", "digraph G {\n  label=\"Stock Levels\"\n  a -> b\n}")
    onBoard(Board.empty): page =>
      waitFor("the hint of an empty board")(page.root.querySelector("#board-empty") != null)
        .flatMap: _ =>
          page.root.querySelector("#board-add [role=button]").dispatchEvent(new dom.FocusEvent("focus"))
          waitFor("the menu has the diagram, with the title of its source")(
            page.root.querySelector(".board-add-menu").textContent.contains("Stock Levels")
          )
        .flatMap: _ =>
          val entries = page.root.querySelectorAll(".board-add-menu a")
          val entry   = (0 until entries.length).map(entries(_)).find(_.textContent == "Stock Levels").getOrElse(fail("no entry"))
          entry.asInstanceOf[dom.HTMLElement].click()
          waitFor("the new frame has a drawing")(page.frames.size == 1 && page.hasDrawing("stock-levels"))
        .map: _ =>
          // The id of the item comes from the name of the diagram, not from its opaque location.
          assertEquals(page.stored.items, Vector(BoardItem("stock-levels", location(stock), 0, 0)))
          assertEquals(page.root.querySelector("#board-empty"), null)

  // Review finding. Graphviz draws an empty graph for a DOT item before its
  // parse ends, and after its parse fails. The view took that drawing as the
  // drawing of the item. Thus a DOT item with an error showed an empty frame
  // and no cause.
  test("a DOT item that does not parse shows the cause, and no drawing"):
    val broken = Library.createDiagram("Broken", brokenDot)
    onBoard(Board(Vector(BoardItem("broken", location(broken), 0, 0)))): page =>
      waitFor(s"the frame shows the cause: ${page.root.innerHTML.take(600)}")(
        page.frames.size == 1 && page.frame("broken").querySelector(".board-item-placeholder .bi-exclamation-triangle") != null
      ).map(_ => assert(!page.hasDrawing("broken"), "the frame shows a drawing of a diagram that does not parse"))

  // Review finding. The first fit of a board used the size of that empty
  // drawing, and the real drawing came later with no new fit. Thus a large
  // drawing went past the canvas.
  test("the first view of a board fits the real size of its drawings"):
    val chain = (1 to 16).map(i => s"node$i").mkString(" -> ")
    val wide  = Library.createDiagram("Wide", s"digraph G {\n  rankdir=LR\n  $chain\n}")
    onBoard(Board(Vector(BoardItem("wide", location(wide), 0, 0)))): page =>
      // The page has no style sheet here, so the canvas gets its size here.
      val canvas = page.root.querySelector(".board-canvas").asInstanceOf[dom.HTMLElement]
      canvas.style.width = "800px"
      canvas.style.height = "600px"
      waitFor("the frame has a drawing")(page.frames.size == 1 && page.hasDrawing("wide"))
        .flatMap(_ => afterMicrotasks(()))
        .map: _ =>
          val width = page.frame("wide").querySelector(".board-item-body svg").getAttribute("width").toDouble
          assert(width > 800, s"precondition: the drawing is wider than the canvas: $width")
          assert(page.scale * width <= 800, s"the first view does not show all of the drawing: the scale is ${page.scale}, the width is $width")

  // Review finding. Each item makes a full ViewerState, and a ViewerState
  // keeps the viewer settings of the window. An item that does not parse
  // opens the Source panel of its state. That panel then became the setting
  // of the next diagram that the person opened.
  test("an item that does not parse does not change the viewer settings of the window"):
    // An earlier suite can leave any panel in the stored settings. Start from a known panel that is not the Source panel.
    val stored = Library.loadViewerSettings()
    stored.set(stored.now().copy(rightPanelTabIndex = RightPanelSection.none.ordinal))
    val before = Library.readViewerSettings().rightPanelTabIndex
    assertEquals(before, RightPanelSection.none.ordinal, "precondition: the stored panel is the panel of the test")
    val broken = Library.createDiagram("Broken", brokenDot)
    onBoard(Board(Vector(BoardItem("broken", location(broken), 0, 0)))): page =>
      waitFor("the frame of the item")(page.frames.size == 1)
        .flatMap: _ =>
          // The parse of the item and its notice come after the frame.
          after(1000)(())
        .map: _ =>
          assertEquals(Library.readViewerSettings().rightPanelTabIndex, before, "a board item changed the stored panel of the window")

  // Review finding. The wait for the drawing of an item started at the mount
  // of the item, not at the end of its parse. All the Mermaid parses and
  // renders wait in one queue. Thus an item that waited in the queue longer
  // than the wait showed a failure, although its render came later. The first
  // fit of the board could then use the size of that failure.
  test("a Mermaid item whose render waits in the queue shows no failure before its drawing"):
    val flow    = Library.createDiagram("Queued flow", mermaid)
    val release = Promise[Unit]()
    // Longer than the wait of 10 s, and shorter than the timeout of the queue (15 s).
    MermaidQueue.holdUntil(release.future)
    js.timers.setTimeout(10500)(release.trySuccess(()))
    onBoard(Board(Vector(BoardItem("flow", location(flow), 0, 0)))): page =>
      // A record of each failure placeholder that the page adds, also one that it removes at once.
      val failures = mutable.Buffer.empty[String]
      val observer = new dom.MutationObserver((records, _) =>
        for
          record <- records
          node   <- (0 until record.addedNodes.length).map(record.addedNodes(_))
        do
          node match
            case element: dom.Element =>
              val failed = element.matches(".bi-exclamation-triangle") || element.querySelector(".bi-exclamation-triangle") != null
              if failed then failures += element.textContent
            case _ => ()
      )
      observer.observe(page.root, new dom.MutationObserverInit { childList = true; subtree = true })
      waitFor("the frame has a drawing", timeoutMs = 20000)(page.frames.size == 1 && page.hasDrawing("flow"))
        .transform: outcome =>
          observer.disconnect()
          release.trySuccess(())
          outcome
        .map(_ => assert(failures.isEmpty, s"the item showed a failure while its render waited in the queue: ${failures.toList}"))

  test("a board that the browser cannot read shows the cause, and no frame"):
    val boardId = BrowserBoards.create("Bad board").fold(message => fail(message), identity)
    dom.window.localStorage.setItem(s"[StoredString]graph-explorer.board.$boardId", """{ "version": 9, "items": [] }""")
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    sharedGraphviz
      .flatMap: graphviz =>
        val store   = BrowserBoardStore(boardId, DefaultDiagramLanguages(graphviz))
        val mounted = render(container, BoardView(store, graphviz, Router(), EventBus[String]()))
        waitFor("the cause is on the page")(container.textContent.contains("version 9")).transform: outcome =>
          mounted.unmount()
          outcome
      .transform: outcome =>
        dom.document.body.removeChild(container)
        outcome
