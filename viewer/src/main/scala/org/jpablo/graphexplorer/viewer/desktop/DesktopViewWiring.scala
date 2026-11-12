package org.jpablo.graphexplorer.viewer.desktop

import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.state.{LeaveIntent, ViewerState}

import scala.concurrent.ExecutionContext

/** The links of one view to the router and to the desktop singletons.
  *
  * `Viewer.diagramView` calls [[attach]] for each view, and the tests call it
  * too. Thus a test drives the same wiring as the app, and not a copy.
  *
  * Each subscription lives on the owner of the view (`state.owner`). The view
  * kills that owner when it unmounts, so a view that went away holds no
  * subscription and no memory in these singletons.
  */
object DesktopViewWiring:

  /** Link `state`, the view that comes on the screen now. Call the function
    * that this returns when the view unmounts.
    *
    * The order is important. `LiveViewCommands` gets its target before
    * `DesktopOpenRequests` can answer `displayed`, so a live view command
    * immediately after `gx open` has a target.
    */
  def attach(state: ViewerState, router: Router)(using ExecutionContext): () => Unit =
    // §7.4: a file with an unsaved edit asks before it is left. One guard
    // exists, and it belongs to the view on the screen.
    val leaveGuard: Route => Boolean = route =>
      if state.documentIsDirty then
        state.pendingLeave.set(Some(LeaveIntent.Navigate(route)))
        false
      else true
    router.guardNavigation(leaveGuard)

    // §7.4's other half: the window closing. The shell refuses that close
    // and asks the page, because it cannot ask during teardown. The page
    // keeps the shell's flag current, so a close with nothing unsaved needs
    // no round trip.
    DesktopClose.install(state)

    // The live view tier answers from HERE (D7.2): a socket client's "what is
    // selected" has no answer anywhere else.
    LiveViewCommands.attach(state)

    // The open handshake settles only after this viewer has attached and
    // accepted its source.
    DesktopOpenRequests.attach(state)

    () =>
      // Each call checks the identity of the view. Laminar mounts the new view
      // BEFORE it unmounts the old view, so an unconditional clear removes the
      // links of the view on the screen.
      LiveViewCommands.detach(state)
      DesktopOpenRequests.detach(state)
      router.clearNavigationGuard(leaveGuard)
      DesktopClose.detach(state)
