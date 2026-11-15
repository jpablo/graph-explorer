package org.jpablo.graphexplorer.gxcore.model

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.BoardLocation.Resolved

/** The board file (ADR 0003).
  *
  * The shape is an API, because a person owns the file and can write it by
  * hand. Thus the literal below is the contract.
  */
class BoardSpec extends FunSuite:

  private val board = Board(
    Vector(
      BoardItem("arch", "arch.dot", 0, 0),
      BoardItem("login", "login.mmd", 900, 0),
      BoardItem("schema", "postgres://db.internal/app", 0, 700, extractor = Some("sql-schema")),
      BoardItem("shot", "assets/screenshot.png", 900.5, 700, width = Some(400))
    )
  )

  private val boardText =
    """{
      |  "version": 1,
      |  "items": [
      |    {
      |      "id": "arch",
      |      "location": "arch.dot",
      |      "x": 0,
      |      "y": 0
      |    },
      |    {
      |      "id": "login",
      |      "location": "login.mmd",
      |      "x": 900,
      |      "y": 0
      |    },
      |    {
      |      "id": "schema",
      |      "location": "postgres://db.internal/app",
      |      "extractor": "sql-schema",
      |      "x": 0,
      |      "y": 700
      |    },
      |    {
      |      "id": "shot",
      |      "location": "assets/screenshot.png",
      |      "x": 900.5,
      |      "y": 700,
      |      "width": 400
      |    }
      |  ]
      |}
      |""".stripMargin

  private def parsed(text: String): Board =
    Board.parse(text).fold(message => fail(s"the board does not parse: $message"), identity)

  private def refused(text: String): String =
    Board.parse(text).fold(identity, board => fail(s"the board parses: $board"))

  // ------------------------------------------------------------- the file

  test("the writer gives the pinned text"):
    assertEquals(Board.render(board), boardText)

  test("the pinned text reads back to the same board"):
    assertEquals(parsed(boardText), board)

  test("the empty board has a version and no items"):
    assertEquals(Board.render(Board.empty), "{\n  \"version\": 1,\n  \"items\": []\n}\n")
    assertEquals(parsed(Board.render(Board.empty)), Board.empty)

  test("the example of ADR 0003 reads, with one line for each item"):
    val adr =
      """{
        |  "version": 1,
        |  "items": [
        |    { "id": "arch",   "location": "arch.dot",                    "x": 0,   "y": 0 },
        |    { "id": "login",  "location": "login.mmd",                   "x": 900, "y": 0 },
        |    { "id": "schema", "location": "postgres://db.internal/app",  "extractor": "sql-schema", "x": 0, "y": 700 },
        |    { "id": "shot",   "location": "assets/screenshot.png",       "x": 900, "y": 700, "width": 400 }
        |  ]
        |}""".stripMargin
    assertEquals(parsed(adr).items.map(_.id), Vector("arch", "login", "schema", "shot"))
    assertEquals(parsed(adr).item("schema").flatMap(_.extractor), Some("sql-schema"))
    assertEquals(parsed(adr).item("shot").flatMap(_.width), Some(400.0))

  test("a board that a person wrote with only the locations reads, and each item is at 0"):
    val byHand = """{ "items": [ { "id": "a", "location": "a.dot" } ] }"""
    assertEquals(parsed(byHand), Board(Vector(BoardItem("a", "a.dot", 0, 0))))

  test("a write keeps each key that this build does not know"):
    val newer =
      """{
        |  "version": 1,
        |  "items": [
        |    {
        |      "id": "note",
        |      "location": "note.md",
        |      "x": 10,
        |      "y": 20,
        |      "height": 300,
        |      "style": {
        |        "color": "yellow"
        |      }
        |    }
        |  ],
        |  "background": "grid"
        |}
        |""".stripMargin
    val read = parsed(newer)
    assertEquals(Board.render(read), newer)
    // A change of the item keeps the keys also.
    val moved = Board.render(read.move("note", 30, 40))
    assert(moved.contains("\"height\": 300") && moved.contains("\"background\": \"grid\""), moved)
    assert(moved.contains("\"x\": 30"), moved)

  test("the reader refuses a version that is newer than the version of this build"):
    val message = refused("""{ "version": 2, "items": [] }""")
    assert(message.contains("version 2"), message)

  test("the reader refuses two items with one id"):
    val message = refused("""{ "items": [ { "id": "a", "location": "a.dot" }, { "id": "a", "location": "b.dot" } ] }""")
    assert(message.contains("the id a"), message)

  test("the reader refuses an item with no id or no location, and names the item"):
    assert(refused("""{ "items": [ { "location": "a.dot" } ] }""").contains("item 1 has no id"))
    assert(refused("""{ "items": [ { "id": "a", "location": "a.dot" }, { "id": "b" } ] }""").contains("item 2 has no location"))

  test("the reader refuses text that is not a board, with a message and no exception"):
    assert(refused("digraph G { a -> b }").contains("not valid JSON"))
    assert(refused("[1, 2]").contains("not a JSON object"))
    assert(refused("""{ "items": [ { "id": "a", "location": "a.dot", "x": "left" } ] }""").contains("the x of item 1"))

  // ----------------------------------------------------------- the changes

  test("a new item gets an id from the name of its location"):
    val (one, first) = Board.empty.add("diagrams/Order Flow.dot", 10, 20)
    assertEquals(first, BoardItem("order-flow", "diagrams/Order Flow.dot", 10, 20))
    val (two, second) = one.add("other/Order Flow.dot", 0, 0)
    assertEquals(second.id, "order-flow-2")
    assertEquals(two.add("Order Flow.mmd", 0, 0)._2.id, "order-flow-3")

  test("a new item for a location with no usable name gets the id item"):
    assertEquals(Board.empty.add("...", 0, 0)._2.id, "item")
    assertEquals(Board.empty.add("postgres://db.internal/app", 0, 0)._2.id, "app")
    assertEquals(Board.empty.add("""C:\work\Arch.dot""", 0, 0)._2.id, "arch")

  test("a new item with a name gets its id from the name, not from the location"):
    val (one, first) = Board.empty.add("browser:3f2a9c1e-77aa", 0, 0, name = Some("Order Flow"))
    assertEquals(first, BoardItem("order-flow", "browser:3f2a9c1e-77aa", 0, 0))
    assertEquals(one.add("browser:91bb", 0, 0, name = Some("Order Flow"))._2.id, "order-flow-2")

  test("move, resize, and remove change only their item"):
    assertEquals(board.move("login", 1, 2).item("login"), Some(BoardItem("login", "login.mmd", 1, 2)))
    assertEquals(board.move("login", 1, 2).items.filterNot(_.id == "login"), board.items.filterNot(_.id == "login"))
    assertEquals(board.resize("arch", Some(250)).item("arch").flatMap(_.width), Some(250.0))
    assertEquals(board.resize("shot", None).item("shot").flatMap(_.width), None)
    assertEquals(board.remove("schema").items.map(_.id), Vector("arch", "login", "shot"))
    assertEquals(board.move("no-such-item", 1, 2), board)

  test("a board path has the board extension, in each case of the letters"):
    assert(Board.isBoardPath("/w/plan.gxboard"))
    assert(Board.isBoardPath("""C:\w\Plan.GXBOARD"""))
    assert(!Board.isBoardPath("/w/plan.gxboard.dot"))
    assert(!Board.isBoardPath("/w/arch.dot"))

  // ---------------------------------------------------------- the locations

  test("a relative location is relative to the folder of the board file"):
    assertEquals(BoardLocation.resolve("/w/boards/plan.gxboard", "arch.dot"), Resolved.File("/w/boards/arch.dot"))
    assertEquals(BoardLocation.resolve("/w/boards/plan.gxboard", "sub/arch.dot"), Resolved.File("/w/boards/sub/arch.dot"))
    assertEquals(BoardLocation.resolve("/w/boards/plan.gxboard", "../arch.dot"), Resolved.File("/w/arch.dot"))
    assertEquals(BoardLocation.resolve("/w/boards/plan.gxboard", "./a/../arch.dot"), Resolved.File("/w/boards/arch.dot"))

  test("an absolute location and a file URI give their file"):
    assertEquals(BoardLocation.resolve("/w/plan.gxboard", "/x/arch.dot"), Resolved.File("/x/arch.dot"))
    assertEquals(BoardLocation.resolve("/w/plan.gxboard", "file:///x/a%20b.dot"), Resolved.File("/x/a b.dot"))

  test("a location with a scheme is a URI, and it does not depend on the board file"):
    assertEquals(
      BoardLocation.resolve("/w/plan.gxboard", "postgres://db.internal/app"),
      Resolved.Uri("postgres://db.internal/app")
    )
    assertEquals(BoardLocation.resolve("/w/plan.gxboard", "browser:abc123"), Resolved.Uri("browser:abc123"))

  test("the two separators of Windows give one file"):
    assertEquals(BoardLocation.resolve("""C:\w\plan.gxboard""", """sub\arch.dot"""), Resolved.File("C:/w/sub/arch.dot"))
    assertEquals(BoardLocation.resolve("C:/w/plan.gxboard", "../arch.dot"), Resolved.File("C:/arch.dot"))
    assertEquals(BoardLocation.resolve("C:/w/plan.gxboard", """D:\x\arch.dot"""), Resolved.File("D:/x/arch.dot"))

  test("a location above the root stays at the root"):
    assertEquals(BoardLocation.resolve("/plan.gxboard", "../../arch.dot"), Resolved.File("/arch.dot"))

  test("the stored location of a file is relative to the board file, and it resolves to the file"):
    val cases = List(
      ("/w/boards/plan.gxboard", "/w/boards/arch.dot", "arch.dot"),
      ("/w/boards/plan.gxboard", "/w/boards/sub/arch.dot", "sub/arch.dot"),
      ("/w/boards/plan.gxboard", "/w/arch.dot", "../arch.dot"),
      ("/w/boards/plan.gxboard", "/x/y/arch.dot", "../../x/y/arch.dot"),
      ("/plan.gxboard", "/w/arch.dot", "w/arch.dot"),
      ("""C:\w\plan.gxboard""", """C:\w\sub\arch.dot""", "sub/arch.dot")
    )
    for (boardPath, filePath, location) <- cases do
      assertEquals(BoardLocation.relativize(boardPath, filePath), location, s"$boardPath, $filePath")
      assertEquals(
        BoardLocation.resolve(boardPath, location),
        Resolved.File(filePath.replace('\\', '/')),
        s"$boardPath, $location"
      )

  test("a file on a different drive keeps its absolute path"):
    assertEquals(BoardLocation.relativize("C:/w/plan.gxboard", """D:\x\arch.dot"""), "D:/x/arch.dot")

  test("a file name that looks like a URI gets a path prefix"):
    val location = BoardLocation.relativize("/w/plan.gxboard", "/w/ab:c.dot")
    assertEquals(location, "./ab:c.dot")
    assertEquals(BoardLocation.resolve("/w/plan.gxboard", location), Resolved.File("/w/ab:c.dot"))
