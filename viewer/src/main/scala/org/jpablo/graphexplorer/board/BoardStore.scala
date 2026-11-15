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

/** A board of the web app. Its items refer to diagrams of the browser library. */
final class BrowserBoardStore(id: String, languages: DiagramLanguages) extends BoardStore:

  val name: Signal[String] = BrowserBoards.index.map(_.boards.find(_.id == id).fold("")(_.name))

  def load(): Future[Either[String, Board]] = Future.successful(BrowserBoards.read(id))

  def save(board: Board): Future[Option[String]] = Future.successful(BrowserBoards.write(id, board))

  def target(item: BoardItem): Future[Option[ViewTarget]] =
    Future.successful:
      BrowserBoards.diagramOf(item.location).filter(Library.diagramExists).map(ViewTarget.Diagram(_))

  def candidates(): Future[List[BoardCandidate]] =
    Future.successful:
      Library.indexNow().diagrams.map: diagram =>
        // The name that the card of the diagram shows in the library.
        val name = Library.diagramCardInfo(diagram.id, languages).map(_.displayName).getOrElse(diagram.name)
        BoardCandidate(name, BrowserBoards.locationOf(diagram.id))
