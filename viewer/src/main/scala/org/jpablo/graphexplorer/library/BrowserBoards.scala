package org.jpablo.graphexplorer.library

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.gxcore.model.Board
import org.jpablo.graphexplorer.viewer.models.DiagramId
import upickle.default.ReadWriter

import scala.util.Try

/** One board in the list of the boards. */
case class BoardSummary(id: String, name: String, lastModified: Long, createdAt: Long) derives ReadWriter, CanEqual

case class BoardIndex(boards: List[BoardSummary] = Nil) derives ReadWriter

/** The boards of the web app (ADR 0003).
  *
  * A browser has no files, so the browser keeps each board. The stored text
  * of a board is the text of a board file (`Board.render`). Thus a later
  * export to a file needs no change of the shape.
  *
  * An item of a browser board refers to a diagram of the browser library. Its
  * location is `browser:<diagram id>` ([[locationOf]]).
  *
  * Each read goes to the storage, and nothing stays in memory. A board is
  * small, and a second tab can change the storage. A write of a second tab
  * comes to this tab as a `storage` event ([[writesOfOtherTabs]]). [[write]]
  * does no check of the stored text. The store of a board page
  * (`BrowserBoardStore`) compares the stored text with [[textOf]] before it
  * writes, so that it does not write over the board of a second tab.
  */
object BrowserBoards:

  private val IndexKey = "graph-explorer.boards"

  private val unreadableIndex =
    "the browser cannot read the stored list of the boards. A later version of the app possibly wrote it."

  private def boardKey(id: String): String = s"graph-explorer.board.$id"

  /** The scheme of the location of a diagram in the browser library. */
  private val Scheme = "browser:"

  def locationOf(id: DiagramId): String = s"$Scheme${id.value}"

  def diagramOf(location: String): Option[DiagramId] =
    Option.when(location.startsWith(Scheme) && location.length > Scheme.length)(DiagramId(location.stripPrefix(Scheme)))

  // A write of the index moves this value, and the index signal reads the storage again.
  private val writes = Var(0)

  val index: Signal[BoardIndex] = writes.signal.map(_ => indexNow())

  /** The list of the boards. It is empty when the stored list does not parse.
    * A write to the list does not use this value ([[updateIndex]]).
    */
  def indexNow(): BoardIndex = storedIndex().getOrElse(BoardIndex())

  /** The stored list of the boards, or the cause when the browser cannot read
    * it. No stored list gives an empty list.
    */
  private def storedIndex(): Either[String, BoardIndex] =
    Try(BrowserLibrary.readKey(IndexKey)).toEither.left
      .map(_ => unreadableIndex)
      .flatMap:
        case None       => Right(BoardIndex())
        case Some(json) => Try(upickle.default.read[BoardIndex](json)).toEither.left.map(_ => unreadableIndex)

  /** Change the stored list of the boards. The result is the cause of a
    * refusal, if there is a refusal.
    *
    * This GUARD prevents the loss of the list. The stored list can have a shape
    * that this version cannot read: a damaged key, or the shape of a later
    * version. [[indexNow]] then gives an empty list. A change of that empty
    * list gives a list of zero or one board, and a write of it replaces the
    * stored list. The other boards stay in the storage, but no list shows
    * them. Thus a list that does not parse stays, and the change does not
    * occur.
    */
  private def updateIndex(change: List[BoardSummary] => List[BoardSummary]): Either[String, Unit] =
    storedIndex()
      .flatMap: current =>
        Try(BrowserLibrary.writeLocalStorage(IndexKey, upickle.default.write(BoardIndex(change(current.boards))))).toEither.left
          .map(_.getMessage)
      .map(_ => writes.update(_ + 1))

  private def refusal(cause: String): String =
    s"The browser did not save the board: $cause"

  def exists(id: String): Boolean = indexNow().boards.exists(_.id == id)

  def nameOf(id: String): Option[String] = indexNow().boards.find(_.id == id).map(_.name)

  /** The name for a new board: `Board`, or `Board` with the first free number. */
  def nextName(): String =
    val taken = indexNow().boards.map(_.name).toSet
    Iterator.single("Board").concat(Iterator.from(2).map(n => s"Board $n")).find(name => !taken.contains(name)).get

  /** Make an empty board. The result is its id, or the message of a refusal. */
  def create(name: String): Either[String, String] =
    val id  = DiagramId.random.value
    val now = System.currentTimeMillis()
    // A check of the list comes first. Thus a refusal writes no board that no list shows.
    val made =
      for
        _ <- storedIndex()
        _ <- Try(BrowserLibrary.writeLocalStorage(boardKey(id), Board.render(Board.empty))).toEither.left.map(_.getMessage)
        _ <- updateIndex(BoardSummary(id, name, lastModified = now, createdAt = now) :: _)
      yield id
    made.left.map(refusal)

  /** The stored text of the board. `None` when the storage has no board. */
  def textOf(id: String): Option[String] =
    Try(BrowserLibrary.readKey(boardKey(id))).toOption.flatten

  /** The number of items of the board, as a Signal. `None` when the board
    * cannot be read. A write of this tab reads the board again.
    */
  def itemCount(id: String): Signal[Option[Int]] =
    writes.signal.map(_ => read(id).toOption.map(_.items.size)).distinct

  /** The board in `text`, or the cause when there is no text or it does not parse. */
  def parse(text: Option[String]): Either[String, Board] =
    text match
      case None       => Left("The browser has no such board.")
      case Some(text) => Board.parse(text)

  /** The board, or the cause when the storage has no board or its text does not parse. */
  def read(id: String): Either[String, Board] = parse(textOf(id))

  /** An event for each write of a different tab to the board `id`.
    *
    * The browser sends a `storage` event to each other tab of the origin, and
    * not to the tab that wrote. An event with no key comes from a `clear` of
    * the storage, so it also has an effect on the board. The listener stays
    * only while a subscriber stays.
    */
  def writesOfOtherTabs(id: String): EventStream[Unit] =
    val key = BrowserLibrary.rawKey(boardKey(id))
    windowEvents(_.onStorage).filter(event => event.key == null || event.key == key).mapToUnit

  /** Write the board, with no check of the stored text. The result is the
    * message of a refusal, if the browser refused.
    *
    * The write of the board does not need the list of the boards. Thus a list
    * that the browser cannot read stops only the change of the time in the
    * list. The board stays saved, and the message tells the person.
    */
  def write(id: String, board: Board): Option[String] =
    Try(BrowserLibrary.writeLocalStorage(boardKey(id), Board.render(board))).toEither.left
      .map(error => refusal(error.getMessage))
      .flatMap: _ =>
        updateIndex(_.map(b => if b.id == id then b.copy(lastModified = System.currentTimeMillis()) else b)).left
          .map(cause => s"The browser saved the board, but it did not change the list of the boards: $cause")
      .left
      .toOption

  def rename(id: String, name: String): Option[String] =
    updateIndex(_.map(b => if b.id == id then b.copy(name = name) else b)).left.toOption.map(refusal)

  /** Delete the board. The diagrams of its items stay in the library.
    *
    * When the browser cannot read the list of the boards, the board stays. A
    * removal of the board would then delete a board that the list possibly
    * still has.
    */
  def delete(id: String): Unit =
    storedIndex() match
      case Left(cause) => Library.showNotice(Vector(s"The browser did not delete the board: $cause"))
      case Right(_) =>
        Try(BrowserLibrary.removeLocalStorage(boardKey(id)))
        updateIndex(_.filterNot(_.id == id)).left.foreach(cause => Library.showNotice(Vector(s"The browser did not delete the board: $cause")))
