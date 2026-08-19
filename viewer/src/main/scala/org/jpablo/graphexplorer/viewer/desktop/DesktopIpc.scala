package org.jpablo.graphexplorer.viewer.desktop

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.util.{Failure, Success}

/** The webview's half of the desktop boundary (architecture D3).
  *
  * The page used to be handed the control server's `port` and `token` on every
  * `document.changed` event and reach the desktop by credentialed cross-origin
  * `fetch`. That made the ONE principal that renders content the user did not
  * write — a `.dot` from anywhere, an imported project — the holder of a bearer
  * token for a general HTTP API.
  *
  * It now calls three named Tauri commands. That is not a sandbox: page JS can
  * still invoke them. What it buys is that the capability is *enumerated and
  * revocable* rather than *ambient*, and that there is no credential in the JS
  * heap to steal in the first place.
  */
object DesktopIpc:

  val OpenDocument  = "open_document"
  val SaveDocument  = "save_document"
  val ListDocuments = "list_documents"

  /** What a save can turn into. `Unavailable` is a first-class outcome rather
    * than an exception: the same viewer runs in an ordinary browser tab, where
    * there is no desktop and no local file to save to.
    */
  enum SaveOutcome derives CanEqual:
    case Saved(path: String, revision: String)
    case Conflict(currentRevision: Option[String])
    case Failed(message: String)
    case Unavailable

  /** `window.__TAURI__.core.invoke`, present only inside the desktop shell
    * (`withGlobalTauri`). Resolved per call rather than cached: `main()` runs
    * before we can be sure the runtime has finished installing it.
    */
  private def invoker: Option[js.Function2[String, js.Any, js.Promise[js.Any]]] =
    def child(parent: js.Dynamic, name: String): Option[js.Dynamic] =
      val value = parent.selectDynamic(name)
      if js.isUndefined(value) || value == null then None else Some(value)

    for
      tauri <- child(js.Dynamic.global.window, "__TAURI__")
      core <- child(tauri, "core")
      invoke <- child(core, "invoke") if js.typeOf(invoke) == "function"
    yield invoke.asInstanceOf[js.Function2[String, js.Any, js.Promise[js.Any]]]

  def available: Boolean = invoker.isDefined

  def invoke(command: String, args: js.Any): Future[js.Any] =
    invoker match
      case Some(fn) => fn(command, args).toFuture
      case None     => Future.failed(js.JavaScriptException("desktop IPC is unavailable"))

  /** Compare-and-swap save. `baseRevision` is the revision the UI last saw, so
    * a file that moved underneath us comes back as a conflict instead of a
    * silent clobber.
    *
    * Note the argument object: `path`, `text`, `baseRevision` — and nothing
    * else. V-11 is asserted against exactly these keys.
    */
  def saveDocument(path: String, text: String, baseRevision: String)(using
      ExecutionContext
  ): Future[SaveOutcome] =
    if !available then Future.successful(SaveOutcome.Unavailable)
    else
      val args = js.Dynamic.literal(
        path = path,
        text = text,
        // D1: a hex content hash, not a number. This used to need
        // `.toDouble` — Scala.js would otherwise hand serde an opaque
        // RuntimeLong for a `u64`. A string crosses as itself, so the
        // workaround goes with the counter that needed it.
        baseRevision = baseRevision
      )
      invoke(SaveDocument, args).transform:
        case Success(value) =>
          val snapshot = value.asInstanceOf[js.Dynamic]
          Success(
            SaveOutcome.Saved(
              path = asString(snapshot, "path").getOrElse(path),
              revision = asString(snapshot, "revision").getOrElse(baseRevision)
            )
          )
        case Failure(error) => Success(failureOutcome(error))

  /** A rejected `invoke` carries the command's `Err` value — our `IpcError`, as
    * a plain JS object. Anything else (the runtime itself failing) still has to
    * reach the user as a message rather than an unhandled rejection.
    */
  private def failureOutcome(error: Throwable): SaveOutcome =
    val rejection: js.Any = error match
      case js.JavaScriptException(value) => value.asInstanceOf[js.Any]
      case other                         => other.getMessage
    if js.typeOf(rejection) == "object" && rejection != null then
      val obj = rejection.asInstanceOf[js.Dynamic]
      asString(obj, "code") match
        case Some("DOCUMENT_CONFLICT") =>
          SaveOutcome.Conflict(asString(obj, "currentRevision"))
        case _ =>
          SaveOutcome.Failed(asString(obj, "message").getOrElse(String.valueOf(rejection)))
    else SaveOutcome.Failed(String.valueOf(rejection))

  private[desktop] def asString(value: js.Any, name: String): Option[String] =
    field(value, name).collect { case v if js.typeOf(v) == "string" => v.asInstanceOf[String] }

  private[desktop] def asLong(value: js.Any, name: String): Option[Long] =
    field(value, name).collect { case v if js.typeOf(v) == "number" => v.asInstanceOf[Double].toLong }

  private def field(value: js.Any, name: String): Option[js.Any] =
    if js.isUndefined(value) || value == null then None
    else
      val selected = value.asInstanceOf[js.Dynamic].selectDynamic(name)
      if js.isUndefined(selected) || selected == null then None else Some(selected)
