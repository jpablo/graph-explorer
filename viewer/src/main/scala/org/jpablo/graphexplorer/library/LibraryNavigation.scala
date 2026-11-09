package org.jpablo.graphexplorer.library

import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.models.DiagramId

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

  /** The text of the control that takes a diagram out of the library. On the
    * desktop, the file stays, so the control does not say "delete".
    */
  def removeLabel: String = if Library.isDesktop then "Remove from library" else "Delete diagram"

  def removeQuestion: String =
    if Library.isDesktop then "Remove this diagram from the library? The file stays where it is."
    else "Delete this diagram?"
