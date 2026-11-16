package org.jpablo.graphexplorer.board

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.gxcore.model.{Board, BoardItem}
import org.jpablo.graphexplorer.library.{BrowserBoards, Library}
import org.jpablo.graphexplorer.router.Route
import org.jpablo.graphexplorer.viewer.backends.DiagramLanguages
import org.jpablo.graphexplorer.viewer.state.ViewTarget

import scala.concurrent.Future

/** One diagram that a person can put on a board: its name, and the location
  * that the board stores for it.
  */
final case class BoardCandidate(name: String, location: String) derives CanEqual

/** Where a board is, and how the app gets the data source of an item
  * (ADR 0003).
  *
  * The board view depends on this trait alone. The web app keeps a board in
  * the browser ([[BrowserBoardStore]]). The desktop app will keep it as a
  * file. Each operation gives a `Future`, because a file store asks the shell.
  */
trait BoardStore:

  /** The name of the board, for the title of the page. */
  def name: Signal[String]

  /** The board, or the cause when the store cannot read it. */
  def load(): Future[Either[String, Board]]

  /** Write the board. The result is the message of a refusal, if there is one. */
  def save(board: Board): Future[Option[String]]

  /** Each version of the board that this page did not write, or the cause
    * when the store cannot read that version. The page then shows it.
    */
  def changes: EventStream[Either[String, Board]]

  /** What a viewer shows for this item. `None` for an item whose data source
    * is missing. The item then stays on the board, marked as missing.
    */
  def target(item: BoardItem): Future[Option[ViewTarget]]

  /** The diagrams that a person can add to the board now. */
  def candidates(): Future[List[BoardCandidate]]

object BoardStore:

  /** The route of the view that shows only this diagram. */
  def routeOf(target: ViewTarget): Option[Route] = target match
    case ViewTarget.Diagram(id)   => Some(Route.Diagram(id.value))
    case ViewTarget.File(session) => Some(Route.File(session.value))
    case ViewTarget.Example(_, _) => None

/** A board of the web app. Its items refer to diagrams of the browser library.
  *
  * A second tab can show the same board and write it. The store keeps the
  * stored text that this page saw last: the text of the load, of a save, or
  * of a write of a different tab. A save writes only over that text. When
  * the storage holds a different text, a different tab wrote the board and
  * this page did not see it yet. The save then refuses, and [[changes]]
  * gives the stored version to the page.
  */
final class BrowserBoardStore(id: String, languages: DiagramLanguages) extends BoardStore:

  val name: Signal[String] = BrowserBoards.index.map(_.boards.find(_.id == id).fold("")(_.name))

  // The stored text that this page saw last. `None` before the load, and
  // when the storage has no board.
  private var seen: Option[String] = None

  // Read the stored text, and keep it as the text that this page saw.
  private def readStored(): Either[String, Board] =
    seen = BrowserBoards.textOf(id)
    BrowserBoards.parse(seen)

  def load(): Future[Either[String, Board]] = Future.successful(readStored())

  // The stored versions that a save found and did not write over.
  private val refused = EventBus[Either[String, Board]]()

  private val otherTab = "A different tab changed this board, so the app did not save it. Make the change again."

  def save(board: Board): Future[Option[String]] =
    val text   = Board.render(board)
    val stored = BrowserBoards.textOf(id)
    Future.successful:
      if stored.contains(text) then
        seen = stored
        None
      else if stored != seen then
        refused.emit(readStored())
        Some(otherTab)
      else
        val refusal = BrowserBoards.write(id, board)
        // The browser can refuse the write. Then the storage keeps the old text.
        seen = BrowserBoards.textOf(id)
        refusal

  /** Each version of the board that a different tab wrote. The browser tells
    * this page with a `storage` event. A save that finds a version of a
    * different tab gives it here also.
    */
  val changes: EventStream[Either[String, Board]] =
    EventStream.merge(
      BrowserBoards
        .writesOfOtherTabs(id)
        // A save of this page can see the version before its event comes.
        .filter(_ => BrowserBoards.textOf(id) != seen)
        .map(_ => readStored()),
      refused.events
    )

  def target(item: BoardItem): Future[Option[ViewTarget]] =
    Future.successful:
      BrowserBoards.diagramOf(item.location).filter(Library.diagramExists).map(ViewTarget.Diagram(_))

  def candidates(): Future[List[BoardCandidate]] =
    Future.successful:
      Library.indexNow().diagrams.map: diagram =>
        // The name that the card of the diagram shows in the library.
        val name = Library.diagramCardInfo(diagram.id, languages).map(_.displayName).getOrElse(diagram.name)
        BoardCandidate(name, BrowserBoards.locationOf(diagram.id))
