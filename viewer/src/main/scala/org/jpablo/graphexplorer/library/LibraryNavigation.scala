package org.jpablo.graphexplorer.library

import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.models.DiagramId
import org.jpablo.graphexplorer.viewer.state.DiagramSnapshot
import org.jpablo.graphexplorer.viewer.utils.ShareUrl

import scala.concurrent.ExecutionContext.Implicits.global

/** Open a diagram of the library, on either platform.
  *
  * In the web app, a diagram of the library has its own route. On the
  * desktop, a diagram is a file: it opens through the path of a file, with a
  * document session (`DesktopLibrary.open`). The open is async there, so a
  * failure goes to `reportError`.
  */
object LibraryNavigation:

  def open(router: Router, id: DiagramId, reportError: String => Unit): Unit =
    Library.desktop match
      case None => router.navigateTo(Route.Diagram(id.value))
      case Some(desktop) =>
        desktop.open(id).foreach:
          case Right(route)  => router.navigateTo(route)
          case Left(message) => reportError(message)

  /** Open the diagram of a share link (`/diagrams/<id>?dot=...`) in the web app.
    *
    * The link opens the diagram of its path when the library has it. Else it
    * opens a diagram with the same text. Else it makes a new diagram with the
    * text of the link. The new diagram stores the text at once, because a visit
    * with no edit writes nothing.
    *
    * The router replaces the share URL in the history ([[Router.redirectTo]]).
    * With a new history entry, Back went to the share URL again. Its route had
    * the old text of the link, and that text replaced the edits of the person.
    */
  def openShareLink(router: Router, dot: String): Unit =
    // The path of the link holds the diagram id. When that diagram is in the
    // local library, open IT. A match by exact source alone made a new
    // "Untitled" copy each time the owner opened their own link after an edit.
    ShareUrl.readDiagramIdFromPath().filter(Library.diagramExists) match
      case Some(pathId) => router.redirectTo(Route.Diagram(pathId.value))
      case None =>
        Library.findDiagramBySource(dot) match
          case Some(existingId) => router.redirectTo(Route.Diagram(existingId.value))
          case None =>
            val newId = Library.createDiagram(DiagramSnapshot.defaultDiagramName, dot)
            // The route keeps the text too. When the storage refuses the
            // payload, the viewer still starts with the text of the link.
            router.redirectTo(Route.Diagram(newId.value, Some(dot)))

  /** The text of the control that takes a diagram out of the library. On the
    * desktop, the file stays, so the control does not say "delete".
    */
  def removeLabel: String = if Library.isDesktop then "Remove from library" else "Delete diagram"

  def removeQuestion: String =
    if Library.isDesktop then "Remove this diagram from the library? The file stays where it is."
    else "Delete this diagram?"
