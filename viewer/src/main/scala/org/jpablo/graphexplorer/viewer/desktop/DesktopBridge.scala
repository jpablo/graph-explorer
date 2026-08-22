package org.jpablo.graphexplorer.viewer.desktop

import org.jpablo.graphexplorer.viewer.state.{DocumentSessionId, ViewerState, ViewTarget}
import org.scalajs.dom

import scala.scalajs.js

/** Wiring between the desktop shell and the current diagram view.
  *
  * Lifted out of `Viewer.main` so the boundary has a name and a test. The
  * install is process-global (one window, one listener) while the target moves
  * with navigation, which is why the state lives here rather than in a class.
  */
object DesktopBridge:

  private val DocumentChangedEventName         = "ge:document.changed"
  private val DocumentChangedFallbackEventName = "document.changed"

  case class DesktopMessage(text: String, path: Option[String], revision: Option[String]) derives CanEqual

  private var installed                   = false
  private var target: Option[ViewerState] = None

  private[desktop] def reset(): Unit =
    installed = false
    target = None

  private[desktop] def currentTarget: Option[ViewerState] = target

  /** Release a viewer that is going away, so nothing keeps talking to it.
    *
    * The listener is process-global while the target moves with navigation, so
    * without this an unmounted viewer stays the target and every later file
    * event is applied to it — which is how opening a loose file could write its
    * source into whichever library record was last displayed.
    *
    * The identity check is not defensive padding. Laminar mounts the incoming
    * view BEFORE unmounting the outgoing one, so the old viewer's detach
    * arrives after the new one has already attached; clearing unconditionally
    * would drop the live target and leave the window deaf. Reference identity
    * (`eq`) is the question being asked — is this still the same object.
    */
  def detach(state: ViewerState): Unit =
    if target.exists(_ eq state) then target = None

  def attach(state: ViewerState): Unit =
    target = Some(state)
    if !installed then
      val handler: js.Function1[dom.Event, Unit] = event =>
        extractMessage(event).foreach: message =>
          // Recorded FIRST, and with no viewer required. An open request routes
          // to a SESSION, so the session has to exist before the request
          // arrives — and an open issued on Home arrives with nothing attached,
          // which is exactly when this used to drop the document entirely (§1).
          val session = recordedSession(message)
          target.foreach(applyDocumentChange(_, message, session))

      dom.window.addEventListener(DocumentChangedEventName, handler)
      dom.window.addEventListener(DocumentChangedFallbackEventName, handler)

      // Imperative fallback for desktop wrappers, and the surface Commands'
      // ⌘S reaches for:
      // window.__graphExplorerDesktopBridge.pushText("...")
      val bridge = js.Dynamic.literal(
        pushText = (text: String) =>
          // Same replacement as the event path above — including the ordering
          // rule that ImportOps.replaceSource documents.
          target.foreach(_.replaceSourceDetectingFormat(text))
      )
      js.Dynamic.global.window.updateDynamic("__graphExplorerDesktopBridge")(bridge)
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

  /** What a document event does to the viewer that is showing that file (§7.3).
    *
    * The rule, in the order it is decided:
    *
    *   1. The editor has no edit: adopt the file. This is a reload, and it is
    *      the normal case — the person changed the file in another tool and
    *      wants to see it.
    *   2. The editor has an edit, and the file changed too: a conflict. Both
    *      versions are kept, and the editor keeps its text. §7.3 forbids the
    *      silent replacement that used to happen here.
    *
    * Before this, EVERY event replaced the text. An external write while the
    * person was typing threw the typing away with no message.
    */
  private[desktop] def applyDocumentChange(state: ViewerState, message: DesktopMessage): Unit =
    applyDocumentChange(state, message, recordedSession(message))

  private[desktop] def applyDocumentChange(
      state:   ViewerState,
      message: DesktopMessage,
      session: Option[(DocumentSessionId, Option[DesktopDocumentRegistry.Session])]
  ): Unit =
    session match
      case None =>
        // A text push (`/v1/push-text`): text with no document behind it, aimed
        // at whatever is on screen. That is what the command is for.
        state.replaceSourceDetectingFormat(message.text)

      case Some((id, previous)) if !showsSession(state, id) =>
        // The viewer is showing something else. Writing this text into it is
        // the misrouting §1 lists — a loose file's source landing in whichever
        // record was last displayed. The session is recorded, and `gx open`
        // routes to it.
        dom.console.debug("Desktop bridge: a document event for a file this viewer does not show.")

      case Some((id, previous))
          if previous.exists(_.sourceText != state.sourceText.now()) // `local` moved: an edit
            && previous.exists(_.sourceText != message.text) =>      // `remote` moved: the file
        // The edit stays on screen. The file's text waits in the session, and
        // the person chooses between them (§7.3).
        DesktopDocumentRegistry.markConflict(id, message.revision.getOrElse(""), message.text)

      case _ =>
        state.replaceSourceDetectingFormat(message.text)

  private def showsSession(state: ViewerState, id: DocumentSessionId): Boolean =
    state.target match
      case ViewTarget.LooseFile(shown) => shown == id
      case _                           => false

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
