package org.jpablo.graphexplorer.viewer.desktop

import org.jpablo.graphexplorer.router.Route
import org.jpablo.graphexplorer.viewer.state.{DiagramLoadStatus, ViewTarget, ViewerState}
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
  private var current: Option[ViewerState] = None
  private var pending = Map.empty[Long, Pending]

  private case class Pending(route: Route, view: js.Object)

  private[desktop] def reset(): Unit =
    installed = false
    current = None
    pending = Map.empty

  /** Listen once, for the life of the window.
    *
    * Process-global like the other desktop listeners, and deliberately NOT
    * scoped to a mounted viewer: an open request routinely arrives while the
    * app is on Home, which is exactly when there is no viewer to attach to.
    */
  def install(navigate: Route => Boolean, exists: String => Boolean)(using ExecutionContext): Unit =
    if !installed then
      val handler: js.Function1[dom.Event, Unit] = event =>
        val requestId = DesktopIpc.asLong(event.asInstanceOf[js.Dynamic].selectDynamic("detail"), "requestId")
        decide(event, exists) match
          case Decision.Show(route, view) =>
            requestId.foreach(id => pending = pending.updated(id, Pending(route, view)))
            if navigate(route) then settlePending()
            else
              requestId.foreach: id =>
                pending -= id
                complete(id, "rejected", Some("ACTIVATION_REFUSED"), Some("the current diagram refused navigation"))
          case Decision.Reject(code, message) =>
            // Answered rather than left to time out. The caller can act on "no
            // such diagram" or "no such document"; it can do nothing useful
            // with forty-five seconds of silence.
            requestId.foreach(complete(_, "rejected", Some(code), Some(message)))

      dom.window.addEventListener(EventName, handler)
      installed = true

      // §4.1: the socket answers from process start, before this page exists,
      // so an open issued in that window would be dispatched into a page with
      // no listener. Announcing readiness AFTER the listener is installed is
      // what makes the shell's queued requests safe to deliver.
      DesktopIpc.invoke(ViewerReady, js.Dynamic.literal()).failed.foreach: error =>
        dom.console.debug(s"viewer_ready is unavailable outside the desktop shell: ${error.getMessage}")

  /** Point the handshake at the viewer that is now on screen.
    *
    * Session commands attach first, then this method waits for parsing. Therefore a displayed
    * acknowledgment means an immediate `gx session` call has a live target.
    */
  def attach(state: ViewerState)(using ExecutionContext): Unit =
    current = Some(state)
    state.loadStatus.signal.changes.foreach(_ => settlePending())(using state.owner)
    settlePending()

  def detach(state: ViewerState): Unit =
    if current.exists(_ eq state) then current = None

  private def settlePending()(using ExecutionContext): Unit =
    current.foreach: state =>
      pending.toVector.foreach: (requestId, request) =>
        activation(request.route, state.target, state.loadStatus.now()) match
          case Activation.Waiting => ()
          case Activation.Displayed =>
            pending -= requestId
            complete(requestId, "displayed", view = Some(request.view))
          case Activation.Rejected(code, message) =>
            pending -= requestId
            complete(requestId, "rejected", Some(code), Some(message))

  private[desktop] enum Activation derives CanEqual:
    case Waiting
    case Displayed
    case Rejected(code: String, message: String)

  private[desktop] def activation(route: Route, target: ViewTarget, status: DiagramLoadStatus): Activation =
    val targetMatches = (route, target) match
      case (Route.ProjectDetail(routeId, _), ViewTarget.LibraryDiagram(projectId)) => routeId == projectId.value
      case (Route.LooseDocument(routeId), ViewTarget.LooseFile(sessionId))         => routeId == sessionId.value
      case _                                                                       => false

    if !targetMatches then Activation.Waiting
    else
      status match
        case DiagramLoadStatus.Loading         => Activation.Waiting
        case DiagramLoadStatus.Ready           => Activation.Displayed
        case DiagramLoadStatus.RenderOnly(_)   => Activation.Displayed
        case DiagramLoadStatus.Failed(message) => Activation.Rejected("PARSE_FAILED", message)

  private val ViewerReady  = "viewer_ready"
  private val CompleteOpen = "complete_open"

  private def complete(
      requestId: Long,
      status:    String,
      code:      Option[String] = None,
      message:   Option[String] = None,
      view:      Option[js.Object] = None
  )(using ExecutionContext): Unit =
    // A JS number on the wire: the id is a u64 counter that will not approach
    // 2^53 in a process lifetime, and Scala.js would otherwise send a RuntimeLong
    // object that serde cannot read as a number.
    val args = js.Dynamic.literal(requestId = requestId.toDouble, status = status)
    code.foreach(c => args.updateDynamic("code")(c))
    message.foreach(m => args.updateDynamic("message")(m))
    view.foreach(v => args.updateDynamic("view")(v))
    DesktopIpc.invoke(CompleteOpen, args).failed.foreach: error =>
      dom.console.warn(s"could not acknowledge an open request: ${error.getMessage}")

  /** What an open request should become.
    *
    * Separated from the listener so the decision is testable without a window.
    * A rejection carries its own code, because the caller's next move differs:
    * a missing diagram is a name to fix, a missing document is a file the shell
    * never delivered, and an unknown kind is a version mismatch.
    */
  private[desktop] enum Decision derives CanEqual:
    case Show(route: Route, view: js.Object)
    case Reject(code: String, message: String)

  /** @param exists
    *   whether the library holds that record. Passed in rather than reached for
    *   so this module stays testable without a library, and so the check
    *   happens HERE: the shell cannot make it (D2.5 keeps it diagram-ignorant),
    *   and acknowledging an open for a record that is not there would report
    *   success for a page showing an empty diagram.
    */
  private[desktop] def decide(event: dom.Event, exists: String => Boolean): Decision =
    // `{requestId, target: {kind, ...}}` — the request id sits beside the
    // target, not inside it, because it identifies the REQUEST rather than
    // anything about what was asked for (§4).
    val detail = event.asInstanceOf[js.Dynamic].selectDynamic("detail")
    val target = detail.selectDynamic("target")
    val kind   = DesktopIpc.asString(target, "kind")

    def field(name: String) = DesktopIpc.asString(target, name).map(_.trim).filter(_.nonEmpty)

    (kind, field("diagramId"), field("path")) match
      case (Some("library"), Some(diagramId), _) if exists(diagramId) =>
        Decision.Show(
          Route.ProjectDetail(diagramId),
          js.Dynamic.literal(kind = "library", diagramId = diagramId)
        )

      case (Some("library"), Some(diagramId), _) =>
        Decision.Reject("DIAGRAM_NOT_FOUND", s"no diagram '$diagramId' in this library")

      // A file is named by a PATH on the wire and by a SESSION on screen. §13
      // allows the path here — an IPC event may carry one where the document
      // session requires it — and stops it at the route.
      case (Some("file"), _, Some(path)) =>
        DesktopDocumentRegistry.find(path) match
          case Some(session) =>
            Decision.Show(
              Route.LooseDocument(session.id.value),
              js.Dynamic.literal(kind = "file", sessionId = session.id.value, revision = session.revision)
            )
          case None =>
            // The shell delivers the document before it asks for the open, so
            // no session means the delivery did not arrive. Saying so beats
            // routing to an empty viewer and calling it displayed.
            Decision.Reject("DOCUMENT_NOT_FOUND", "the page holds no open document for that path")

      // A request naming nothing, or a kind we do not know: a newer shell
      // talking to an older page. Guessing a route would navigate away from
      // whatever the person is looking at — strictly worse than refusing.
      case _ =>
        Decision.Reject("VIEW_REJECTED", "the page could not route to that target")
