package org.jpablo.graphexplorer.viewer.desktop

import org.jpablo.graphexplorer.router.Route
import org.scalajs.dom

import scala.concurrent.ExecutionContext
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
  /** @param exists
    *   whether the library holds that record. Passed in rather than reached for
    *   so this module stays testable without a library, and so the check
    *   happens HERE: the shell cannot make it (D2.5 keeps it diagram-ignorant),
    *   and acknowledging an open for a record that is not there would report
    *   success for a page showing an empty diagram.
    */
  def install(navigate: Route => Unit, exists: String => Boolean)(using ExecutionContext): Unit =
    if !installed then
      val handler: js.Function1[dom.Event, Unit] = event =>
        val requestId = DesktopIpc.asLong(event.asInstanceOf[js.Dynamic].selectDynamic("detail"), "requestId")
        route(event) match
          case Some(Route.ProjectDetail(id, _)) if !exists(id) =>
            // Answered immediately rather than left to time out. The caller can
            // act on "there is no such diagram"; it can do nothing useful with
            // forty-five seconds of silence.
            requestId.foreach(
              complete(_, "rejected", Some("DIAGRAM_NOT_FOUND"), Some(s"no diagram '$id' in this library"))
            )
          case Some(target) =>
            navigate(target)
            // Acknowledged only after the route is set. `gx open` is waiting on
            // this: until it arrives the shell knows only that it dispatched an
            // event, which is not evidence that anything reached the screen.
            requestId.foreach(complete(_, "displayed"))
          case None =>
            // Say so rather than let the shell wait out its timeout. A request
            // naming a record this page cannot route to is answered, not
            // ignored — the caller deserves the reason, not a stall.
            requestId.foreach(
              complete(_, "rejected", Some("VIEW_REJECTED"), Some("the page could not route to that target"))
            )
      dom.window.addEventListener(EventName, handler)
      installed = true

      // §4.1: the socket answers from process start, before this page exists,
      // so an open issued in that window would be dispatched into a page with
      // no listener. Announcing readiness AFTER the listener is installed is
      // what makes the shell's queued requests safe to deliver.
      DesktopIpc.invoke(ViewerReady, js.Dynamic.literal()).failed.foreach: error =>
        dom.console.debug(s"viewer_ready is unavailable outside the desktop shell: ${error.getMessage}")

  private val ViewerReady  = "viewer_ready"
  private val CompleteOpen = "complete_open"

  private def complete(
      requestId: Long,
      status:    String,
      code:      Option[String] = None,
      message:   Option[String] = None
  )(using ExecutionContext): Unit =
    // A JS number on the wire: the id is a u64 counter that will not approach
    // 2^53 in a process lifetime, and Scala.js would otherwise send a RuntimeLong
    // object that serde cannot read as a number.
    val args = js.Dynamic.literal(requestId = requestId.toDouble, status = status)
    code.foreach(c => args.updateDynamic("code")(c))
    message.foreach(m => args.updateDynamic("message")(m))
    DesktopIpc.invoke(CompleteOpen, args).failed.foreach: error =>
      dom.console.warn(s"could not acknowledge an open request: ${error.getMessage}")

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
