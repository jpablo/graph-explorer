package org.jpablo.graphexplorer.viewer.desktop

import org.jpablo.graphexplorer.viewer.state.DocumentSessionId
import org.scalajs.dom

import scala.scalajs.js

/** Wiring between the desktop shell and the current diagram view.
  *
  * Lifted out of `Viewer.main` so the boundary has a name and a test. The
  * install is process-global (one window, one listener) while the target moves
  * with navigation, which is why the state lives here rather than in a class.
  */
object DesktopBridge:

  private val DocumentChangedEventName = "ge:document.changed"

  case class DesktopMessage(text: String, path: Option[String], revision: Option[String]) derives CanEqual

  private var installed = false

  private[desktop] def reset(): Unit = installed = false

  /** Listen for document events, for the life of the window.
    *
    * Called at STARTUP, not on the first attach. This listener used to be
    * installed lazily by `attach`, and `attach` runs only when a diagram view
    * mounts — so a desktop sitting on Home had no listener at all. The document
    * event carrying a file's text was dropped, and the open request that
    * followed found no session and answered DOCUMENT_NOT_FOUND.
    *
    * That made `gx open <path>` fail on a freshly started desktop and succeed
    * once any diagram had been opened, which is the worst shape a bug can take:
    * it disappears exactly when someone tries to reproduce it.
    *
    * CAUTION: this must run BEFORE `DesktopOpenRequests.install`. That call
    * announces `viewer_ready`, and the shell delivers its queued opens on that
    * signal — including the document events they depend on.
    */
  def install(): Unit =
    if !installed then
      val handler: js.Function1[dom.Event, Unit] = event =>
        extractMessage(event).foreach(routeDocumentChange)

      // ONE event name, and a namespaced one. The bare `document.changed` was
      // also accepted, which meant any script on the page could push text into
      // the viewer by dispatching a DOM event. The shell has always sent the
      // namespaced name as well, so nothing that matters loses a listener.
      dom.window.addEventListener(DocumentChangedEventName, handler)

      installed = true
      dom.console.info("Desktop bridge listener installed.")

  /** The desktop dispatches a `CustomEvent`; some wrappers put the same object
    * on `payload` instead of `detail`, and a bare string is accepted as text.
    * Anything the shell no longer sends — notably a credential — has no field
    * here to land in.
    */
  def extractMessage(event: dom.Event): Option[DesktopMessage] =
    val raw = event.asInstanceOf[js.Dynamic]

    def asString(value: js.Any): Option[String] =
      if js.isUndefined(value) || value == null then None
      else if js.typeOf(value) == "string" then Some(value.asInstanceOf[String])
      else None

    def field(value: js.Any, name: String): js.Any =
      if js.isUndefined(value) || value == null then js.undefined
      else value.asInstanceOf[js.Dynamic].selectDynamic(name)

    val detailValue   = raw.selectDynamic("detail")
    val payloadValue  = raw.selectDynamic("payload")
    val payloadText   = asString(field(payloadValue, "text"))
    val detailText    = asString(field(detailValue, "text"))
    val directPayload = asString(payloadValue)
    val detailPayload = asString(detailValue)

    val text = payloadText.orElse(detailText).orElse(directPayload).orElse(detailPayload)
    text.map: t =>
      DesktopMessage(
        text = t,
        path = DesktopIpc.asString(detailValue, "path").orElse(DesktopIpc.asString(payloadValue, "path")),
        revision =
          DesktopIpc.asString(detailValue, "revision").orElse(DesktopIpc.asString(payloadValue, "revision"))
      )

  /** The destination of a document event.
    *
    * A document event goes to a SESSION. It never goes to "the viewer that is
    * on the screen". Before, this module held a process-global pointer to that
    * viewer and applied each file event to it. Because of this, the source of
    * one file could go into the view of a different diagram (§1).
    *
    * Now the module only writes down the event that arrived. The file goes
    * into the registry, and the viewer that shows that session gets its
    * changes through an owner-scoped signal (§10). Each diagram on the desktop
    * is a file (ADR 0001), so no event goes to a library copy.
    */
  private[desktop] def routeDocumentChange(message: DesktopMessage): Unit =
    // `record` is idempotent per path, and a message with no path records
    // nothing.
    recordSession(message)

  /** Record the open file, so a route and a save can find it.
    *
    * A document event is the only place the page learns a path, a revision and
    * the text together. `record` is idempotent per path: a second open of one
    * file keeps the first id, and so keeps the route that names it.
    *
    * A message with no path is a text push (`/v1/push-text`), and it records
    * nothing. The text still reaches the viewer above, where it reads as an
    * unsaved edit — which is what it is.
    */
  private[desktop] def recordSession(message: DesktopMessage): Unit =
    recordedSession(message)

  /** The session this message belongs to, and what it held BEFORE the message.
    *
    * The previous state is returned because the conflict decision needs it: the
    * question is whether the file moved away from the base the editor started
    * from, and `record` has already overwritten that base by the time a caller
    * could look it up again.
    */
  private def recordedSession(
      message: DesktopMessage
  ): Option[(DocumentSessionId, Option[DesktopDocumentRegistry.Session])] =
    for
      path <- message.path
      revision <- message.revision
    yield
      val previous = DesktopDocumentRegistry.find(path)
      val current  = DesktopDocumentRegistry.record(path, revision, message.text)
      (current.id, previous)
