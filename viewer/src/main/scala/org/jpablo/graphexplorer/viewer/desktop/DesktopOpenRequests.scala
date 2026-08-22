package org.jpablo.graphexplorer.viewer.desktop

import org.jpablo.graphexplorer.router.Route
import org.scalajs.dom

import scala.scalajs.js

/** Open requests the shell cannot serve on its own.
  *
  * A LOOSE FILE arrives as `ge:document.changed`, because the shell can read it
  * and hand over the bytes. A LIBRARY RECORD cannot: the shell knows nothing
  * about diagrams (D2.5), the record rather than any file is authoritative for
  * it (§2 of docs/desktop-open-targets-and-persistence.md), and an unbound
  * record has no path to read at all. So `show` with a library target names the
  * record and the page routes to it.
  *
  * That naming is the point. Before this, `gx open` resolved every reference to
  * a path and the diagram id was discarded, so an open could not say WHICH
  * record it meant — and an unbound one could not be opened at all.
  */
object DesktopOpenRequests:

  private val EventName = "ge:open.requested"

  private var installed = false

  private[desktop] def reset(): Unit = installed = false

  /** Listen once, for the life of the window.
    *
    * Process-global like the other desktop listeners, and deliberately NOT
    * scoped to a mounted viewer: an open request routinely arrives while the
    * app is on Home, which is exactly when there is no viewer to attach to.
    */
  def install(navigate: Route => Unit): Unit =
    if !installed then
      val handler: js.Function1[dom.Event, Unit] = event => route(event).foreach(navigate)
      dom.window.addEventListener(EventName, handler)
      installed = true

  /** The route an open request asks for, if it asks for one we understand.
    *
    * Separated from the listener so the decision is testable without a window,
    * and returns an Option rather than throwing: this is untrusted input from
    * outside the page, and an unrecognised shape must be ignored rather than
    * become an error the user sees.
    */
  private[desktop] def route(event: dom.Event): Option[Route] =
    val detail = event.asInstanceOf[js.Dynamic].selectDynamic("detail")
    val kind   = DesktopIpc.asString(detail, "kind")
    val id     = DesktopIpc.asString(detail, "diagramId").map(_.trim).filter(_.nonEmpty)

    (kind, id) match
      case (Some("library"), Some(diagramId)) => Some(Route.ProjectDetail(diagramId))
      // A library request with no id names nothing, and a kind we do not know
      // is a newer shell talking to an older page. Neither is actionable, and
      // guessing a route would navigate away from whatever the user is looking
      // at — strictly worse than doing nothing.
      case _ => None
