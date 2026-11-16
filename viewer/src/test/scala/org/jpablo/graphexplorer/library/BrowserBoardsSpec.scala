package org.jpablo.graphexplorer.library

import com.raquo.airstream.ownership.ManualOwner
import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.{Board, BoardItem}
import org.jpablo.graphexplorer.viewer.models.DiagramId
import org.jpablo.graphexplorer.viewer.utils.TestHelpers

/** The boards of the web app, in the storage of the browser (ADR 0003). */
class BrowserBoardsSpec extends FunSuite with TestHelpers:

  override def munitFixtures = List(mockStorageFixture())

  private def created(name: String): String =
    BrowserBoards.create(name).fold(message => fail(s"the browser refused the board: $message"), identity)

  test("a new board is empty, and the list has it"):
    val id = created("Plan")
    assertEquals(BrowserBoards.read(id), Right(Board.empty))
    assertEquals(BrowserBoards.nameOf(id), Some("Plan"))
    assert(BrowserBoards.exists(id))
    assert(!BrowserBoards.exists("no-such-board"))

  test("a write keeps the board, and it moves the time of the board in the list"):
    val id     = created("Plan")
    val before = BrowserBoards.indexNow().boards.find(_.id == id).get
    val board  = Board(Vector(BoardItem("a", "browser:abc", 10, 20), BoardItem("b", "browser:def", 300, 0, width = Some(250))))
    assertEquals(BrowserBoards.write(id, board), None)
    assertEquals(BrowserBoards.read(id), Right(board))
    val after = BrowserBoards.indexNow().boards.find(_.id == id).get
    assert(after.lastModified >= before.lastModified)
    assertEquals(after.createdAt, before.createdAt)

  test("the list signal shows a new board and a delete"):
    val owner = ManualOwner()
    val ids   = BrowserBoards.index.map(_.boards.map(_.id).toSet).observe(using owner)
    val id    = created("Plan")
    assert(ids.now().contains(id))
    BrowserBoards.delete(id)
    assert(!ids.now().contains(id))
    assertEquals(BrowserBoards.read(id), Left("The browser has no such board."))
    owner.killSubscriptions()

  test("a rename changes only the name"):
    val id = created("Plan")
    assertEquals(BrowserBoards.rename(id, "Architecture"), None)
    assertEquals(BrowserBoards.nameOf(id), Some("Architecture"))
    assertEquals(BrowserBoards.read(id), Right(Board.empty))

  test("the name of a new board is the first name that no board has"):
    assertEquals(BrowserBoards.nextName(), "Board")
    created("Board")
    assertEquals(BrowserBoards.nextName(), "Board 2")
    created("Board 2")
    created("Board 4")
    assertEquals(BrowserBoards.nextName(), "Board 3")

  test("the location of a browser diagram has the browser scheme, and it gives the diagram back"):
    val id = DiagramId("abc-123")
    assertEquals(BrowserBoards.locationOf(id), "browser:abc-123")
    assertEquals(BrowserBoards.diagramOf("browser:abc-123"), Some(id))
    assertEquals(BrowserBoards.diagramOf("arch.dot"), None)
    assertEquals(BrowserBoards.diagramOf("browser:"), None)

  // Review finding. The list of the boards read as empty when its stored text
  // did not parse, and the next write wrote that empty list. Thus each other
  // board left the list, and nothing showed it again.
  test("a write and a create do not empty a list of boards that the browser cannot read"):
    val keep = created("Keep")
    val id   = created("Plan")
    BrowserLibrary.writeLocalStorage("graph-explorer.boards", """{ "boards": "a shape of a later build" }""")
    BrowserBoards.write(id, Board.empty)
    BrowserBoards.create("New")
    val stored = BrowserLibrary.readKey("graph-explorer.boards").getOrElse("")
    assert(
      stored.contains("a shape of a later build") || stored.contains(keep),
      s"a write replaced the list of the boards that the browser could not read: $stored"
    )

  test("a stored board that does not parse gives its cause"):
    val id = created("Plan")
    BrowserLibrary.writeLocalStorage(s"graph-explorer.board.$id", """{ "version": 9, "items": [] }""")
    assert(BrowserBoards.read(id).left.exists(_.contains("version 9")), BrowserBoards.read(id))
