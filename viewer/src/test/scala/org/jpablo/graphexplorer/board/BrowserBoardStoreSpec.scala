package org.jpablo.graphexplorer.board

import com.raquo.airstream.core.Observer
import com.raquo.airstream.ownership.{ManualOwner, Owner}
import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.{Board, BoardItem}
import org.jpablo.graphexplorer.library.BrowserBoards
import org.jpablo.graphexplorer.viewer.backends.DefaultDiagramLanguages
import org.jpablo.graphexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** A board of the web app in two tabs (ADR 0003).
  *
  * The two tabs share the storage of the browser. A write of one tab sends a
  * `storage` event to each other tab, and not to the tab that wrote. Here the
  * two tabs are in one page, so a test sends that event itself.
  *
  * Review finding. The store of a browser board had no change from outside,
  * and each commit wrote the whole board of the page. Thus a board that a
  * second tab wrote was lost with no message.
  */
class BrowserBoardStoreSpec extends FunSuite with TestHelpers:

  override def munitFixtures = List(mockStorageFixture())

  private lazy val sharedGraphviz: Future[Graphviz] = TestHelpers.sharedGraphviz

  private val start = Board(Vector(BoardItem("y", "browser:y", 0, 0)))

  /** The board page of one tab, with the messages that it gives. */
  private final class Tab(id: String, graphviz: Graphviz)(using Owner):
    val errors = mutable.Buffer.empty[String]
    val state  = BoardState(BrowserBoardStore(id, DefaultDiagramLanguages(graphviz)), Observer[String](errors += _))

  /** A board in the storage, and one tab on it after the load. */
  private def withTab(body: (String, Tab) => Future[Unit]): Future[Unit] =
    val owner = ManualOwner()
    sharedGraphviz
      .flatMap: graphviz =>
        val id = BrowserBoards.create("Plan").fold(message => fail(s"the browser refused the board: $message"), identity)
        assertEquals(BrowserBoards.write(id, start), None)
        val tab = Tab(id, graphviz)(using owner)
        afterMicrotasks(assertEquals(tab.state.board.now(), start, "precondition: the tab shows the stored board"))
          .flatMap(_ => body(id, tab))
      .transform: outcome =>
        owner.killSubscriptions()
        outcome

  /** A second tab writes `board`. With `event`, the browser tells this tab. */
  private def secondTabWrites(id: String, board: Board, event: Boolean): Unit =
    assertEquals(BrowserBoards.write(id, board), None)
    if event then
      // The key in the storage, as `BrowserLibrary.rawKey` gives it.
      val init = js.Dynamic.literal(key = s"[StoredString]graph-explorer.board.$id", newValue = Board.render(board))
      dom.window.dispatchEvent(js.Dynamic.newInstance(js.Dynamic.global.StorageEvent)("storage", init).asInstanceOf[dom.Event])
      ()

  private val theirs = start.add("browser:x", 400, 0)._1

  test("a board that a second tab writes shows in this tab, and the next commit of this tab keeps it"):
    withTab: (id, tab) =>
      secondTabWrites(id, theirs, event = true)
      afterMicrotasks(())
        .flatMap: _ =>
          assertEquals(tab.state.board.now(), theirs, "this tab does not show the board of the second tab")
          tab.state.move("y", 50, 50)
          tab.state.commit()
          afterMicrotasks(())
        .map: _ =>
          assertEquals(BrowserBoards.read(id), Right(theirs.move("y", 50, 50)), "the commit of this tab lost the item of the second tab")
          assertEquals(tab.errors.toList, Nil)

  test("a commit does not write over a board that a second tab wrote, before this tab knows it"):
    withTab: (id, tab) =>
      // The event of the second tab did not come yet.
      secondTabWrites(id, theirs, event = false)
      tab.state.move("y", 50, 50)
      tab.state.commit()
      afterMicrotasks:
        assert(
          BrowserBoards.read(id).exists(_.item(theirs.items.last.id).isDefined),
          s"the commit wrote over the item that the second tab added: ${BrowserBoards.read(id)}"
        )
        assert(
          tab.errors.nonEmpty || tab.state.board.now().item(theirs.items.last.id).isDefined,
          "the tab does not show the board of the second tab, and the person got no message"
        )
