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
  * small, and a second tab can change the storage.
  */
object BrowserBoards:

  private val IndexKey = "graph-explorer.boards"

  private def boardKey(id: String): String = s"graph-explorer.board.$id"

  /** The scheme of the location of a diagram in the browser library. */
  private val Scheme = "browser:"

  def locationOf(id: DiagramId): String = s"$Scheme${id.value}"

  def diagramOf(location: String): Option[DiagramId] =
    Option.when(location.startsWith(Scheme) && location.length > Scheme.length)(DiagramId(location.stripPrefix(Scheme)))

  // A write of the index moves this value, and the index signal reads the storage again.
  private val writes = Var(0)

  val index: Signal[BoardIndex] = writes.signal.map(_ => indexNow())

  def indexNow(): BoardIndex =
    Try(BrowserLibrary.readKey(IndexKey)).toOption.flatten
      .flatMap(json => Try(upickle.default.read[BoardIndex](json)).toOption)
      .getOrElse(BoardIndex())

  private def updateIndex(change: List[BoardSummary] => List[BoardSummary]): Try[Unit] =
    Try(BrowserLibrary.writeLocalStorage(IndexKey, upickle.default.write(BoardIndex(change(indexNow().boards)))))
      .map(_ => writes.update(_ + 1))

  private def refusal(error: Throwable): String =
    s"The browser did not save the board: ${error.getMessage}"

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
    Try(BrowserLibrary.writeLocalStorage(boardKey(id), Board.render(Board.empty)))
      .flatMap(_ => updateIndex(BoardSummary(id, name, lastModified = now, createdAt = now) :: _))
      .toEither
      .left
      .map(refusal)
      .map(_ => id)

  /** The board, or the cause when the storage has no board or its text does not parse. */
  def read(id: String): Either[String, Board] =
    Try(BrowserLibrary.readKey(boardKey(id))).toOption.flatten match
      case None       => Left("The browser has no such board.")
      case Some(text) => Board.parse(text)

  /** Write the board. The result is the message of a refusal, if the browser refused. */
  def write(id: String, board: Board): Option[String] =
    Try(BrowserLibrary.writeLocalStorage(boardKey(id), Board.render(board)))
      .flatMap: _ =>
        updateIndex(_.map(b => if b.id == id then b.copy(lastModified = System.currentTimeMillis()) else b))
      .failed
      .toOption
      .map(refusal)

  def rename(id: String, name: String): Option[String] =
    updateIndex(_.map(b => if b.id == id then b.copy(name = name) else b)).failed.toOption.map(refusal)

  /** Delete the board. The diagrams of its items stay in the library. */
  def delete(id: String): Unit =
    Try(BrowserLibrary.removeLocalStorage(boardKey(id)))
    updateIndex(_.filterNot(_.id == id)).failed.foreach(error => Library.showNotice(Vector(refusal(error))))
