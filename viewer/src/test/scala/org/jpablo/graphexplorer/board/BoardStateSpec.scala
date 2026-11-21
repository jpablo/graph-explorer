package org.jpablo.graphexplorer.board

import com.raquo.airstream.core.{EventStream, Observer, Signal, Transaction}
import com.raquo.airstream.eventbus.EventBus
import com.raquo.airstream.ownership.ManualOwner
import com.raquo.airstream.state.Val
import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.{Board, BoardItem}
import org.jpablo.graphexplorer.viewer.state.ViewTarget
import org.jpablo.graphexplorer.viewer.utils.{TestHelpers, TimerHelpers}

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/** The state of a board on the screen: the items, their places, and the
  * writes to the store (ADR 0003).
  *
  * The store here keeps the board in memory, and it counts the writes.
  */
class BoardStateSpec extends FunSuite with TestHelpers with TimerHelpers:

  private final class MemoryStore(start: Either[String, Board], refusal: Option[String] = None) extends BoardStore:
    val saves                                               = mutable.Buffer.empty[Board]
    val outside                                             = EventBus[Either[String, Board]]()
    val changes: EventStream[Either[String, Board]]         = outside.events
    val name: Signal[String]                                = Val("Test board")
    def load(): Future[Either[String, Board]]               = Future.successful(start)
    def save(board: Board): Future[Option[String]]          = { saves += board; Future.successful(refusal) }
    def target(item: BoardItem): Future[Option[ViewTarget]] = Future.successful(None)
    def candidates(): Future[List[BoardCandidate]]          = Future.successful(Nil)

  private val two = Board(Vector(BoardItem("a", "a.dot", 0, 0), BoardItem("b", "b.dot", 500, 100)))

  // A short wait for the first fit, so that the tests of the wait are fast.
  private val fitWaitMs = 200.0

  // The fit of the board `two` at the end of the wait, when item "a" has its
  // size (400 x 300) and "b" has the default size.
  private val defaultFit = BoardViewport.fit(BoardBounds(0, 0, 820, 340), 1000, 600)

  // The owner of the states of one test. The end of the test kills it.
  private var owner = ManualOwner()

  override def beforeEach(context: BeforeEach): Unit = owner = ManualOwner()

  override def afterEach(context: AfterEach): Unit = owner.killSubscriptions()

  /** A state on `store`, after the load. */
  private def loaded(store: MemoryStore): Future[(BoardState, mutable.Buffer[String])] =
    val errors = mutable.Buffer.empty[String]
    val state  = BoardState(store, Observer[String](errors += _))(using owner)
    afterMicrotasks((state, errors))

  test("the state loads the board from the store, and the load writes nothing"):
    val store = MemoryStore(Right(two))
    loaded(store).map: (state, _) =>
      assertEquals(state.status.now(), BoardStatus.Ready)
      assertEquals(state.board.now(), two)
      assertEquals(store.saves.size, 0)

  test("a move changes the board with no write, and the commit gives one write"):
    val store = MemoryStore(Right(two))
    loaded(store)
      .flatMap: (state, _) =>
        state.move("a", 10.4, 20.6)
        state.move("a", 30.2, 40.7)
        assertEquals(store.saves.size, 0)
        // The places are whole numbers, so the file stays clean.
        assertEquals(state.board.now().item("a"), Some(BoardItem("a", "a.dot", 30, 41)))
        state.commit()
        afterMicrotasks(state)
      .map: state =>
        assertEquals(store.saves.toList, List(state.board.now()))

  test("a new item goes to the right of the items, and the store gets it"):
    val store = MemoryStore(Right(two))
    loaded(store)
      .flatMap: (state, _) =>
        state.itemSize("a", 200, 100)
        state.itemSize("b", 300, 400)
        state.add("c.dot")
        afterMicrotasks(state)
      .map: state =>
        // The items end at x = 800. The new item starts after the gap, at the top of the items.
        assertEquals(state.board.now().item("c"), Some(BoardItem("c", "c.dot", 800 + BoardState.Gap, 0)))
        assertEquals(state.selected.now(), Some("c"))
        assertEquals(store.saves.toList, List(state.board.now()))

  test("the first item of a board goes to the origin"):
    val store = MemoryStore(Right(Board.empty))
    loaded(store).map: (state, _) =>
      state.add("first.dot")
      assertEquals(state.board.now().items, Vector(BoardItem("first", "first.dot", 0, 0)))

  test("a remove takes the item from the board and from the selection, and the store gets the board"):
    val store = MemoryStore(Right(two))
    loaded(store)
      .flatMap: (state, _) =>
        state.selected.set(Some("a"))
        state.remove("a")
        afterMicrotasks(state)
      .map: state =>
        assertEquals(state.board.now().items.map(_.id), Vector("b"))
        assertEquals(state.selected.now(), None)
        assertEquals(store.saves.toList, List(state.board.now()))

  test("a board that the store cannot read shows the cause, and no change writes over it"):
    val store = MemoryStore(Left("the board file has version 2"))
    loaded(store)
      .flatMap: (state, _) =>
        assertEquals(state.status.now(), BoardStatus.Failed("the board file has version 2"))
        state.add("a.dot")
        state.move("a", 1, 2)
        state.remove("a")
        state.commit()
        afterMicrotasks(state)
      .map: state =>
        assertEquals(state.board.now(), Board.empty)
        assertEquals(store.saves.size, 0, "a change wrote over a board that the app cannot read")

  test("a version of the board from a different writer shows, and it gives no write"):
    val store  = MemoryStore(Right(two))
    val theirs = two.move("a", 700, 700).remove("b")
    loaded(store).map: (state, _) =>
      state.selected.set(Some("b"))
      store.outside.emit(Right(theirs))
      assertEquals(state.board.now(), theirs)
      assertEquals(state.selected.now(), None, "the selection names an item that the board does not have")
      assertEquals(store.saves.size, 0)

  test("a version that the store cannot read stops the writes, and a later good version starts them again"):
    val store = MemoryStore(Right(two))
    loaded(store)
      .flatMap: (state, _) =>
        store.outside.emit(Left("the board file is not valid JSON"))
        assertEquals(state.status.now(), BoardStatus.Failed("the board file is not valid JSON"))
        state.move("a", 1, 2)
        state.commit()
        afterMicrotasks(state)
      .flatMap: state =>
        assertEquals(store.saves.size, 0, "a change wrote over a board file that the app cannot read")
        store.outside.emit(Right(two))
        assertEquals(state.status.now(), BoardStatus.Ready)
        state.move("a", 1, 2)
        state.commit()
        afterMicrotasks(state)
      .map: state =>
        assertEquals(store.saves.toList, List(state.board.now()))

  test("a refusal of the store goes to the person"):
    val store = MemoryStore(Right(two), refusal = Some("The browser did not save the board: the storage is full"))
    loaded(store)
      .flatMap: (state, errors) =>
        state.commit()
        afterMicrotasks(errors)
      .map: errors =>
        assertEquals(errors.toList, List("The browser did not save the board: the storage is full"))

  test("the first view shows all of the board, after the view measured each item"):
    val store = MemoryStore(Right(two))
    loaded(store).map: (state, _) =>
      state.canvasSize = () => (1000, 600)
      state.itemSize("a", 400, 300)
      assertEquals(state.viewport.now(), BoardViewport.start, "the fit did not wait for the second item")
      state.itemSize("b", 1500, 900)
      // The items go from (0, 0) to (2000, 1000).
      assertEquals(state.bounds, Some(BoardBounds(0, 0, 2000, 1000)))
      assertEquals(state.viewport.now(), BoardViewport.fit(BoardBounds(0, 0, 2000, 1000), 1000, 600))

  test("a later size does not move a view that the person moved"):
    val store = MemoryStore(Right(two))
    loaded(store).map: (state, _) =>
      state.canvasSize = () => (1000, 600)
      state.panBy(15, 25)
      val moved = state.viewport.now()
      state.itemSize("a", 400, 300)
      state.itemSize("b", 1500, 900)
      assertEquals(state.viewport.now(), moved)
      // The Fit control still shows all of the board.
      state.fit()
      assertEquals(state.viewport.now(), BoardViewport.fit(BoardBounds(0, 0, 2000, 1000), 1000, 600))

  test("after an add, the view shows all of the board when the view measured the new item"):
    val store = MemoryStore(Right(two))
    loaded(store).map: (state, _) =>
      state.canvasSize = () => (1000, 600)
      state.itemSize("a", 400, 300)
      state.itemSize("b", 300, 200)
      state.panBy(-5000, 0)
      val away = state.viewport.now()
      state.add("c.dot")
      assertEquals(state.viewport.now(), away, "the fit did not wait for the size of the new item")
      state.itemSize("c", 200, 100)
      // The items go from (0, 0) to (800, 300), and the new item is at x = 860.
      assertEquals(state.viewport.now(), BoardViewport.fit(BoardBounds(0, 0, 1060, 300), 1000, 600))

  // Review finding of PR #62. The first fit waited for the size of each item,
  // with no limit. An item gives its size only when its render ends, and all
  // the Mermaid renders wait in one queue. Thus a board with many slow items
  // showed no first fit for a long time.

  /** A state on `store` with a short wait for the first fit, after the load. */
  private def loadedWithWait(store: MemoryStore, waitMs: Double = fitWaitMs): Future[BoardState] =
    val state = BoardState(store, Observer.empty, waitMs)(using owner)
    state.canvasSize = () => (1000, 600)
    afterMicrotasks(state)

  test("the first view shows all of the board after the wait, also when an item has no size"):
    val store = MemoryStore(Right(two))
    loadedWithWait(store)
      .flatMap: state =>
        state.itemSize("a", 400, 300)
        after(fitWaitMs + 300)(state)
      .map: state =>
        // The item "b" has the default size: it goes from (500, 100) to (820, 340).
        assertEquals(state.viewport.now(), BoardViewport.fit(BoardBounds(0, 0, 820, 340), 1000, 600), "the first fit did not come after the wait")

  test("after the wait, the size of the last item gives a new fit"):
    val store = MemoryStore(Right(two))
    loadedWithWait(store)
      .flatMap: state =>
        state.itemSize("a", 400, 300)
        after(fitWaitMs + 300)(state)
      .map: state =>
        state.itemSize("b", 1500, 900)
        assertEquals(state.viewport.now(), BoardViewport.fit(BoardBounds(0, 0, 2000, 1000), 1000, 600), "the size of the last item gave no fit")

  test("the wait does not move a view that the person moved"):
    val store = MemoryStore(Right(two))
    loadedWithWait(store)
      .flatMap: state =>
        state.panBy(15, 25)
        val moved = state.viewport.now()
        after(fitWaitMs + 300)((state, moved))
      .map: (state, moved) =>
        assertEquals(state.viewport.now(), moved, "the end of the wait moved the view of the person")
        state.itemSize("a", 400, 300)
        state.itemSize("b", 1500, 900)
        assertEquals(state.viewport.now(), moved, "a later size moved the view of the person")

  test("after an add, the view shows all of the board after the wait, also when the new item has no size"):
    val store = MemoryStore(Right(two))
    loadedWithWait(store)
      .flatMap: state =>
        state.itemSize("a", 400, 300)
        state.itemSize("b", 300, 200)
        state.panBy(-5000, 0)
        state.add("c.dot")
        after(fitWaitMs + 300)(state)
      .map: state =>
        // The items go from (0, 0) to (800, 300). The new item is at x = 860,
        // and it has the default size.
        assertEquals(state.viewport.now(), BoardViewport.fit(BoardBounds(0, 0, 1180, 300), 1000, 600), "the fit after the add did not come after the wait")
        state.itemSize("c", 200, 100)
        assertEquals(state.viewport.now(), BoardViewport.fit(BoardBounds(0, 0, 1060, 300), 1000, 600), "the size of the new item gave no fit")

  // Review findings of PR #63.

  test("after the wait, a move of an item stops the later fit"):
    val store = MemoryStore(Right(two))
    loadedWithWait(store)
      .flatMap: state =>
        state.itemSize("a", 400, 300)
        after(fitWaitMs + 300)(state)
      .map: state =>
        assertEquals(state.viewport.now(), defaultFit)
        // The person drags an item. Then the render of "b" ends.
        state.move("a", 10, 10)
        state.itemSize("b", 1500, 900)
        assertEquals(state.viewport.now(), defaultFit, "the size of the last item moved the view during the work of the person")

  test("an add during the first wait does not put off the first fit"):
    val waitMs = 400.0
    val store  = MemoryStore(Right(two))
    loadedWithWait(store, waitMs)
      .flatMap: state =>
        state.itemSize("a", 400, 300)
        // The add comes before the end of the first wait.
        after(waitMs * 0.75)(state.add("c.dot")).map(_ => state)
      .flatMap: state =>
        // The first wait ended at 400 ms. A new wait from the add ends at 700 ms.
        after(waitMs * 0.6)(state)
      .map: state =>
        assertNotEquals(state.viewport.now(), BoardViewport.start, "the add put off the first fit")

  test("after the wait, a remove of the last item with no size gives the fit of the real sizes"):
    val store = MemoryStore(Right(two))
    loadedWithWait(store)
      .flatMap: state =>
        state.itemSize("a", 400, 300)
        after(fitWaitMs + 300)(state)
      .map: state =>
        assertEquals(state.viewport.now(), defaultFit)
        state.remove("b")
        assertEquals(state.viewport.now(), BoardViewport.fit(BoardBounds(0, 0, 400, 300), 1000, 600), "the remove gave no fit")

  test("after the wait, a board from a different writer with no item without a size gives the fit of the real sizes"):
    val store = MemoryStore(Right(two))
    loadedWithWait(store)
      .flatMap: state =>
        state.itemSize("a", 400, 300)
        after(fitWaitMs + 300)(state)
      .map: state =>
        assertEquals(state.viewport.now(), defaultFit)
        store.outside.emit(Right(two.remove("b")))
        assertEquals(state.viewport.now(), BoardViewport.fit(BoardBounds(0, 0, 400, 300), 1000, 600), "the board of the different writer gave no fit")

  test("a wait that ends while the board cannot be read gives a new wait when the board is good again"):
    val store = MemoryStore(Right(two))
    loadedWithWait(store)
      .flatMap: state =>
        state.itemSize("a", 400, 300)
        store.outside.emit(Left("the board file is not valid JSON"))
        after(fitWaitMs + 300)(state)
      .flatMap: state =>
        assertEquals(state.viewport.now(), BoardViewport.start, "a board that the app cannot read got a fit")
        store.outside.emit(Right(two))
        after(fitWaitMs + 300)(state)
      .map: state =>
        assertEquals(state.viewport.now(), defaultFit, "the good board got no fit after the wait")

  test("the end of the owner stops the wait"):
    val store = MemoryStore(Right(two))
    loadedWithWait(store)
      .flatMap: state =>
        state.itemSize("a", 400, 300)
        owner.killSubscriptions()
        after(fitWaitMs + 300)(state)
      .map: state =>
        assertEquals(state.viewport.now(), BoardViewport.start, "the wait fitted the view of a board that is closed")

  test("a zoom during the wait stops the fit"):
    val store = MemoryStore(Right(two))
    loadedWithWait(store)
      .flatMap: state =>
        state.itemSize("a", 400, 300)
        state.zoomBy(2)
        val zoomed = state.viewport.now()
        after(fitWaitMs + 300)((state, zoomed))
      .map: (state, zoomed) =>
        assertEquals(state.viewport.now(), zoomed, "the end of the wait moved the view of the person")
        state.itemSize("b", 1500, 900)
        assertEquals(state.viewport.now(), zoomed, "a later size moved the view of the person")

  // Review findings of PR #64.

  test("a move to the same place, as in a click on the title, does not stop the fit"):
    val store = MemoryStore(Right(two))
    loadedWithWait(store)
      .flatMap: state =>
        state.itemSize("a", 400, 300)
        // A click on the title of "a" gives a pointer move of less than one pixel.
        state.move("a", 0.3, -0.2)
        after(fitWaitMs + 300)(state)
      .map: state =>
        assertEquals(state.board.now().item("a"), Some(BoardItem("a", "a.dot", 0, 0)), "precondition: the item moved")
        assertEquals(state.viewport.now(), defaultFit, "a click on the title stopped the first fit")
        state.itemSize("b", 1500, 900)
        assertEquals(state.viewport.now(), BoardViewport.fit(BoardBounds(0, 0, 2000, 1000), 1000, 600), "a click on the title stopped the last fit")

  test("a move to a different place during the wait stops the fit"):
    val store = MemoryStore(Right(two))
    loadedWithWait(store)
      .flatMap: state =>
        state.itemSize("a", 400, 300)
        // The person drags the item "a" before the end of the wait.
        state.move("a", 10.4, 9.6)
        after(fitWaitMs + 300)(state)
      .map: state =>
        assertEquals(state.board.now().item("a"), Some(BoardItem("a", "a.dot", 10, 10)), "precondition: the item did not move")
        assertEquals(state.viewport.now(), BoardViewport.start, "the end of the wait moved the view during the work of the person")
        state.itemSize("b", 1500, 900)
        assertEquals(state.viewport.now(), BoardViewport.start, "the size of the last item moved the view during the work of the person")

  test("a remove in a transaction gives the fit of the real sizes"):
    val store = MemoryStore(Right(two))
    loadedWithWait(store)
      .flatMap: state =>
        state.itemSize("a", 400, 300)
        after(fitWaitMs + 300)(state)
      .flatMap: state =>
        assertEquals(state.viewport.now(), defaultFit)
        Transaction(_ => state.remove("b"))
        afterMicrotasks(state)
      .map: state =>
        assertEquals(state.board.now().items.map(_.id), Vector("a"), "precondition: the remove did not change the board")
        assertEquals(state.viewport.now(), BoardViewport.fit(BoardBounds(0, 0, 400, 300), 1000, 600), "the remove in a transaction gave no fit")

  test("an add and a remove in a transaction write the new board"):
    val store = MemoryStore(Right(two))
    loaded(store)
      .flatMap: (state, _) =>
        Transaction(_ => state.add("c.dot"))
        afterMicrotasks(state)
      .flatMap: state =>
        assertEquals(store.saves.toList.map(_.items.map(_.id)), List(Vector("a", "b", "c")), "the add in a transaction wrote the old board")
        Transaction(_ => state.remove("a"))
        afterMicrotasks(state)
      .map: state =>
        assertEquals(store.saves.toList.map(_.items.map(_.id)), List(Vector("a", "b", "c"), Vector("b", "c")), "the remove in a transaction wrote the old board")

  test("an add in a transaction waits for the size of the new item"):
    val store = MemoryStore(Right(two))
    loadedWithWait(store)
      .flatMap: state =>
        state.itemSize("a", 400, 300)
        state.itemSize("b", 300, 200)
        Transaction(_ => state.add("c.dot"))
        afterMicrotasks(state)
      .map: state =>
        assertEquals(state.board.now().items.map(_.id), Vector("a", "b", "c"), "precondition: the add did not change the board")
        state.itemSize("c", 200, 100)
        // The items go from (0, 0) to (800, 300), and the new item is at x = 860.
        assertEquals(state.viewport.now(), BoardViewport.fit(BoardBounds(0, 0, 1060, 300), 1000, 600), "the add in a transaction gave no fit for the new item")

  test("the zoom controls change the scale at the center of the canvas"):
    val store = MemoryStore(Right(two))
    loaded(store).map: (state, _) =>
      state.canvasSize = () => (1000, 600)
      val center = state.viewport.now().toBoard(500, 300)
      state.zoomBy(2)
      assertEquals(state.viewport.now().scale, 2.0)
      assertEquals(state.viewport.now().toBoard(500, 300), center)
      state.resetZoom()
      assertEquals(state.viewport.now().scale, 1.0)
