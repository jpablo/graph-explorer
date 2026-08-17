package org.jpablo.graphexplorer.viewer.desktop

import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.scalajs.dom

import scala.concurrent.ExecutionContext
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

  /** What the page knows about the open file: which one, and which revision it
    * last saw. Formerly it also carried `port` and `token` — the credential the
    * webview no longer holds (V-11).
    */
  case class DocumentRef(path: String, revision: Long) derives CanEqual

  case class DesktopMessage(text: String, path: Option[String], revision: Option[Long]) derives CanEqual

  private var installed                = false
  private var target: Option[ViewerState] = None
  private var documentRef: Option[DocumentRef] = None

  private[desktop] def reset(): Unit =
    installed = false
    target = None
    documentRef = None

  private[desktop] def currentDocumentRef: Option[DocumentRef] = documentRef

  def attach(state: ViewerState)(using ExecutionContext): Unit =
    target = Some(state)
    if !installed then
      val handler: js.Function1[dom.Event, Unit] = event =>
        extractMessage(event).foreach: message =>
          updateDocumentRef(message)
          target.foreach(_.replaceSourceDetectingFormat(message.text))

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
        ,
        saveCurrentText = () => saveCurrentText(),
        saveText = (text: String) => saveText(text)
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
          DesktopIpc.asLong(detailValue, "revision").orElse(DesktopIpc.asLong(payloadValue, "revision"))
      )

  private[desktop] def updateDocumentRef(message: DesktopMessage): Unit =
    for
      path <- message.path
      revision <- message.revision
    do documentRef = Some(DocumentRef(path, revision))

  private def saveCurrentText()(using ExecutionContext): Unit =
    target.foreach: state =>
      saveText(state.sourceText.now())

  private def saveText(text: String)(using ExecutionContext): Unit =
    (target, documentRef) match
      case (Some(state), Some(ref)) =>
        DesktopIpc
          .saveDocument(ref.path, text, ref.revision)
          .foreach:
            case DesktopIpc.SaveOutcome.Saved(path, revision) =>
              documentRef = Some(DocumentRef(path, revision))
              state.infoBus.emit("Saved to local file")
            case DesktopIpc.SaveOutcome.Conflict(_) =>
              state.errorBus.emit("Save conflict: file changed on disk. Reload and try again.")
            case DesktopIpc.SaveOutcome.Failed(message) =>
              state.errorBus.emit(s"Save failed: $message")
            case DesktopIpc.SaveOutcome.Unavailable =>
              state.infoBus.emit("Desktop save is unavailable in this mode")
      case (Some(state), None) =>
        state.infoBus.emit("No active watched file for desktop save")
      case _ =>
        ()
