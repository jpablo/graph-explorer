package org.jpablo.graphexplorer.viewer.desktop

import com.raquo.airstream.ownership.Subscription
import com.raquo.laminar.api.L.unsafeWindowOwner
import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.state.{DiagramLoadStatus, ViewTarget, ViewerState}
import org.scalajs.dom

import scala.concurrent.ExecutionContext
import scala.scalajs.js

/** The open requests that the shell cannot do alone.
  *
  * A file comes as `ge:document.changed`, because the shell can read the file
  * and give the bytes. Then `ge:open.requested` asks the page to show it. The
  * page goes to the route of the document session, and answers the shell only
  * when the view shows the file (§4).
  *
  * Each diagram on the desktop is a file (ADR 0001). Thus `show` has one kind
  * of target, `file`. The old `library` kind named a record of the library,
  * and the library keeps no records now.
  */
object DesktopOpenRequests:

  private val EventName = "ge:open.requested"

  /** The answer to a request whose route the screen left before the view
    * showed the file. `await_open` in `main.rs` gives this code to `gx`.
    */
  private[desktop] val OpenSuperseded = "OPEN_SUPERSEDED"

  /** Kept so [[reset]] can REMOVE them. Without the removal, an install after
    * a reset adds a second listener, and both listeners answer one request.
    */
  private var listener: Option[js.Function1[dom.Event, Unit]] = None
  private var routeChanges: Option[Subscription] = None

  private var current: Option[ViewerState] = None
  private var pending = Map.empty[Long, Pending]

  private case class Pending(route: Route, view: js.Object)

  private[desktop] def reset(): Unit =
    listener.foreach(dom.window.removeEventListener(EventName, _))
    listener = None
    routeChanges.foreach(_.kill())
    routeChanges = None
    current = None
    pending = Map.empty

  /** Listen once, for the life of the window.
    *
    * Process-global like the other desktop listeners, and deliberately NOT
    * scoped to a mounted viewer: an open request routinely arrives while the
    * app is on Home, which is exactly when there is no viewer to attach to.
    *
    * It follows the route of the router too. When the screen goes to a
    * different route, each request that waits for a different route gets
    * `OPEN_SUPERSEDED` at once (§4). Before, such a request stayed `Waiting`,
    * and its `gx open` waited for the timeout of the shell (45 s). A second
    * `gx open` during a slow load (Mermaid), a click on Home, and the back
    * button all do this.
    */
  def install(router: Router)(using ExecutionContext): Unit =
    if listener.isEmpty then
      val handler: js.Function1[dom.Event, Unit] = event =>
        val requestId = DesktopIpc.asLong(event.asInstanceOf[js.Dynamic].selectDynamic("detail"), "requestId")
        decide(event) match
          case Decision.Show(route, view) =>
            // Pending BEFORE the navigation. The route change of this
            // navigation supersedes the OTHER requests, and keeps this one.
            requestId.foreach(id => pending = pending.updated(id, Pending(route, view)))
            if router.tryNavigateTo(route) then settlePending()
            else
              requestId.foreach(complete(_, "rejected", Some("ACTIVATION_REFUSED"), Some("the current diagram refused navigation")))
          case Decision.Reject(code, message) =>
            // Answered rather than left to time out. The caller can act on "no
            // such document"; it can do nothing useful with forty-five seconds
            // of silence.
            requestId.foreach(complete(_, "rejected", Some(code), Some(message)))

      dom.window.addEventListener(EventName, handler)
      listener = Some(handler)
      routeChanges = Some(router.currentRoute.changes.foreach(supersede)(using unsafeWindowOwner))

      // §4.1: the socket answers from process start, before this page exists,
      // so an open issued in that window would be dispatched into a page with
      // no listener. Announcing readiness AFTER the listener is installed is
      // what makes the shell's queued requests safe to deliver.
      DesktopIpc.invoke(ViewerReady, js.Dynamic.literal()).failed.foreach: error =>
        dom.console.debug(s"viewer_ready is unavailable outside the desktop shell: ${error.getMessage}")

  /** Point the handshake at the viewer that is now on screen.
    *
    * Live view commands attach first, then this method waits for parsing. Therefore a displayed
    * acknowledgment means an immediate `gx view` call has a live target.
    */
  def attach(state: ViewerState)(using ExecutionContext): Unit =
    current = Some(state)
    state.loadStatus.signal.changes.foreach(_ => settlePending())(using state.owner)
    settlePending()

  def detach(state: ViewerState): Unit =
    if current.exists(_ eq state) then current = None

  /** Answer each request that waits for a route that is not `onScreen`.
    *
    * The route, and not the view, decides. At start the route can change
    * before a view exists (Graphviz loads first), and that request must wait
    * for its view.
    */
  private def supersede(onScreen: Route)(using ExecutionContext): Unit =
    pending.toVector.foreach: (requestId, request) =>
      if request.route != onScreen then
        complete(
          requestId,
          "rejected",
          Some(OpenSuperseded),
          Some("the window went to a different view before it showed the file")
        )

  private def settlePending()(using ExecutionContext): Unit =
    current.foreach: state =>
      pending.toVector.foreach: (requestId, request) =>
        activation(request.route, state.target, state.loadStatus.now()) match
          case Activation.Waiting                 => ()
          case Activation.Displayed               => complete(requestId, "displayed", view = Some(request.view))
          case Activation.Rejected(code, message) => complete(requestId, "rejected", Some(code), Some(message))

  private[desktop] enum Activation derives CanEqual:
    case Waiting
    case Displayed
    case Rejected(code: String, message: String)

  private[desktop] def activation(route: Route, target: ViewTarget, status: DiagramLoadStatus): Activation =
    val targetMatches = (route, target) match
      case (Route.File(routeId), ViewTarget.File(sessionId)) => routeId == sessionId.value
      case _                                                 => false

    if !targetMatches then Activation.Waiting
    else
      status match
        case DiagramLoadStatus.Loading         => Activation.Waiting
        case DiagramLoadStatus.Ready           => Activation.Displayed
        case DiagramLoadStatus.RenderOnly(_)   => Activation.Displayed
        case DiagramLoadStatus.Failed(message) => Activation.Rejected("PARSE_FAILED", message)

  private val ViewerReady  = "viewer_ready"
  private val CompleteOpen = "complete_open"

  /** Answer an open request. A request has one answer, so the answer also
    * ends its wait in [[pending]].
    */
  private def complete(
      requestId: Long,
      status:    String,
      code:      Option[String] = None,
      message:   Option[String] = None,
      view:      Option[js.Object] = None
  )(using ExecutionContext): Unit =
    pending -= requestId
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
    * a missing document is a file the shell never delivered, and an unknown
    * kind is a version mismatch.
    */
  private[desktop] enum Decision derives CanEqual:
    case Show(route: Route, view: js.Object)
    case Reject(code: String, message: String)

  private[desktop] def decide(event: dom.Event): Decision =
    // `{requestId, target: {kind, ...}}` — the request id sits beside the
    // target, not inside it, because it identifies the REQUEST rather than
    // anything about what was asked for (§4).
    val detail = event.asInstanceOf[js.Dynamic].selectDynamic("detail")
    val target = detail.selectDynamic("target")
    val kind   = DesktopIpc.asString(target, "kind")

    def field(name: String) = DesktopIpc.asString(target, name).map(_.trim).filter(_.nonEmpty)

    (kind, field("path")) match
      // A file is named by a PATH on the wire and by a SESSION on screen. §13
      // allows the path here — an IPC event may carry one where the document
      // session requires it — and stops it at the route.
      case (Some("file"), Some(path)) =>
        DesktopDocumentRegistry.find(path) match
          case Some(session) =>
            Decision.Show(
              Route.File(session.id.value),
              js.Dynamic.literal(kind = "file", sessionId = session.id.value, revision = session.revision)
            )
          case None =>
            // The shell delivers the document before it asks for the open, so
            // no session means the delivery did not arrive. Saying so beats
            // routing to an empty viewer and calling it displayed.
            Decision.Reject("DOCUMENT_NOT_FOUND", "the page holds no open document for that path")

      // A request naming nothing, or a kind we do not know: a different shell
      // talking to this page (the old `library` kind is one). Guessing a route
      // would navigate away from whatever the person is looking at — strictly
      // worse than refusing.
      case _ =>
        Decision.Reject("VIEW_REJECTED", "the page could not route to that target")
