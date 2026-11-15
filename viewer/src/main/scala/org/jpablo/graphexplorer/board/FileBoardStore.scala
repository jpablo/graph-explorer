package org.jpablo.graphexplorer.board

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.gxcore.model.{Board, BoardItem, BoardLocation, LibraryEntry}
import org.jpablo.graphexplorer.library.DesktopLibrary
import org.jpablo.graphexplorer.viewer.desktop.{DesktopDocumentRegistry, DesktopIpc}
import org.jpablo.graphexplorer.viewer.state.{DocumentSessionId, ViewTarget}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/** A board of the desktop app: a `.gxboard` file (ADR 0003).
  *
  * The file is the only copy of the board, as the file of a diagram is the
  * only copy of its source (ADR 0001). The store reads the file through its
  * document session, and it writes with the compare-and-swap of the shell.
  * Thus a write of this page never goes over a write of a different tool.
  *
  * A board has no editor and no unsaved state: each change of the person goes
  * to the file at once. Thus the registry always takes a new version of the
  * file as the base, and [[changes]] gives it to the page.
  *
  * `library` gives the diagrams that a person can add. It is `None` in a
  * test, and on a desktop whose library did not load.
  */
final class FileBoardStore(session: DocumentSessionId, library: Option[DesktopLibrary])(using ExecutionContext)
    extends BoardStore:

  private def open: Option[DesktopDocumentRegistry.Session] = DesktopDocumentRegistry.get(session)

  /** The name of a board is the name of its file, with no extension. */
  val name: Signal[String] =
    DesktopDocumentRegistry.signal(session).map(_.fold("")(file => LibraryEntry.nameOf(file.path)))

  def load(): Future[Either[String, Board]] =
    Future.successful(open.toRight("This board file is not open any more.").flatMap(file => Board.parse(file.sourceText)))

  // The text of the save that has no reply yet. The shell sends the event of
  // a save before its reply, and that event is not a change from outside.
  private var saving: Option[String] = None

  /** Each version of the file that this page did not write: a different tool
    * wrote the file, or a person edited it by hand.
    */
  val changes: EventStream[Either[String, Board]] =
    DesktopDocumentRegistry
      .signal(session)
      .changes
      .collect { case Some(file) => file.sourceText }
      .filter(text => !saving.contains(text))
      .map(Board.parse)

  // One save is in flight at a time. A save sends the base revision of the
  // session, and that base moves only when the save before it ends.
  private var lastSave: Future[Option[String]] = Future.successful(None)

  def save(board: Board): Future[Option[String]] =
    val text = Board.render(board)
    lastSave = lastSave.transformWith(_ => write(text))
    lastSave

  private def write(text: String): Future[Option[String]] =
    open match
      case None                                => Future.successful(Some("This board file is not open any more."))
      case Some(file) if file.sourceText == text => Future.successful(None)
      case Some(file) =>
        saving = Some(text)
        DesktopDocumentRegistry.beginSave(session, text)
        DesktopIpc
          .saveDocument(file.path, text, file.revision)
          .flatMap:
            // The shell lost the watch of the file. Register it again, and
            // try one time more, with the same base (`FilePersistence`).
            case DesktopIpc.SaveOutcome.NotWatched(_) =>
              DesktopIpc.openFile(file.path).flatMap: _ =>
                DesktopDocumentRegistry.beginSave(session, text)
                DesktopIpc.saveDocument(file.path, text, file.revision)
            case outcome => Future.successful(outcome)
          .recover:
            case NonFatal(error) => DesktopIpc.SaveOutcome.Failed(Option(error.getMessage).getOrElse(error.toString))
          .map: outcome =>
            val written = outcome match
              case DesktopIpc.SaveOutcome.Saved(_, revision) => Some(revision)
              case _                                         => None
            DesktopDocumentRegistry.endSave(session, text, written)
            saving = None
            outcome match
              case DesktopIpc.SaveOutcome.Saved(_, _) => None
              case DesktopIpc.SaveOutcome.Conflict(_) =>
                Some("The board file changed on disk, so the app did not write it. Make the change again.")
              case DesktopIpc.SaveOutcome.NotWatched(message) => Some(s"The app did not write the board file: $message")
              case DesktopIpc.SaveOutcome.Failed(message)     => Some(s"The app did not write the board file: $message")
              case DesktopIpc.SaveOutcome.Unavailable         => Some("A board file needs the desktop app.")

  /** The file of an item: the shell opens it, and the page gets a document
    * session for it. A file that the shell cannot open is missing. A location
    * that is not a file has no extractor yet, so it is missing also.
    */
  def target(item: BoardItem): Future[Option[ViewTarget]] =
    open.map(file => BoardLocation.resolve(file.path, item.location)) match
      case Some(BoardLocation.Resolved.File(path)) =>
        DesktopIpc
          .openFile(path)
          .map: snapshot =>
            val opened = DesktopDocumentRegistry.record(snapshot.path, snapshot.revision, snapshot.text)
            Option(ViewTarget.File(opened.id))
          .recover:
            case NonFatal(_) => None
      case _ => Future.successful(None)

  /** The diagrams of the library, each with its location relative to the board file. */
  def candidates(): Future[List[BoardCandidate]] =
    Future.successful:
      for
        lib     <- library.toList
        file    <- open.toList
        diagram <- lib.indexNow().diagrams
        path    <- lib.pathOf(diagram.id)
      yield BoardCandidate(diagram.name, BoardLocation.relativize(file.path, path))
