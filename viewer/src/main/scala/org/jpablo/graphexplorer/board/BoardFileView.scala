package org.jpablo.graphexplorer.board

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.library.Library
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.graphexplorer.viewer.desktop.{DesktopDocumentRegistry, DesktopOpenRequests, LiveViewCommands}
import org.jpablo.graphexplorer.viewer.state.DocumentSessionId

import scala.concurrent.ExecutionContext.Implicits.global

/** The page of a board file of the desktop app (ADR 0003).
  *
  * It is the board page on the store of a file, with two links to the
  * desktop:
  *
  *   - The open handshake. `gx open plan.gxboard` then reports "displayed"
  *     when the page read the board, and it reports a rejection when the
  *     board file does not parse.
  *   - The live view tier. `gx fit plan.gxboard` fits the board, and each
  *     other live command gets a refusal that names the board.
  *
  * The page of the app and the tests call this function, so the tests use
  * this wiring and not a copy.
  */
def BoardFileView(session: DocumentSessionId, graphviz: Graphviz, router: Router, errors: EventBus[String]): Div =
  val owner = new ManualOwner
  BoardView(
    FileBoardStore(session, Library.desktop),
    graphviz,
    router,
    errors,
    wire = board =>
      // The live view tier gets the board before the handshake can answer
      // "displayed", so a live command just after `gx open` has a target.
      LiveViewCommands.attachBoard(board, () => DesktopDocumentRegistry.get(session).map(_.path), () => board.fit())
      DesktopOpenRequests.attachBoard(session, board)(using owner)
      () =>
        LiveViewCommands.detachBoard(board)
        DesktopOpenRequests.detachBoard(board)
        owner.killSubscriptions()
  )
