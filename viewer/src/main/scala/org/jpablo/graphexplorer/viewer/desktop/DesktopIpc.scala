package org.jpablo.graphexplorer.viewer.desktop

import org.jpablo.graphexplorer.gxcore.model.ContentHash
import org.scalajs.dom

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.util.{Failure, Success}

/** No shell to ask. Named rather than anonymous so a caller can tell "the
  * desktop is not here" from "the desktop refused".
  */
object IpcUnavailable extends Exception("desktop IPC is unavailable")

/** The webview's half of the desktop boundary (architecture D3).
  *
  * The page used to be handed the control server's `port` and `token` on every
  * `document.changed` event and reach the desktop by credentialed cross-origin
  * `fetch`. That made the ONE principal that renders content the user did not
  * write — a `.dot` from anywhere, an imported project — the holder of a bearer
  * token for a general HTTP API.
  *
  * It now calls named Tauri commands — the ones listed below. That is not a
  * sandbox: page JS can still invoke them. What it buys is that the capability is *enumerated and
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

  private val HashText = "hash_text"

  def invoke(command: String, args: js.Any): Future[js.Any] =
    invoker match
      case Some(fn) => fn(command, args).toFuture
      case None     => Future.failed(js.JavaScriptException("desktop IPC is unavailable"))

  /** Start watching a record's origin, so a change to it reaches the page.
    *
    * The MISSING link of Phase 3. Reconciliation runs when the shell reports a
    * change, and the shell reports one only for a file it watches — but the
    * page never asked for a watch. `open_document` was defined, handled and
    * documented, and called from nowhere. So an origin edit reached the app
    * only when `gx open` had watched the file, and opening the same record
    * from the library was silent.
    *
    * The reply is DISCARDED on purpose. It carries the bytes, and taking them
    * here would put the file's text on screen without asking the record — the
    * exact behaviour §8 removed. `add_watch` emits a document event for a watch
    * it creates, so the text arrives by the one route that reconciles it. A
    * file that changed while the app was shut therefore reconciles at open.
    *
    * CAUTION: there is no matching unwatch, and this must not grow one without
    * a refcount. Watches are keyed by PATH and shared: `gx watch` may hold one
    * on the same file, and tearing it down because a page navigated would stop
    * a watch the page never started.
    */
  def openDocument(path: String)(using ExecutionContext): Future[Unit] =
    if !available then Future.unit
    else
      invoke(OpenDocument, js.Dynamic.literal(path = path)).map(_ => ()).recover:
        case error =>
          // A watch the shell refused is not fatal to the view. The record
          // still opens; it just will not hear about the file.
          dom.console.warn(s"[origin] could not watch $path: ${error.getMessage}")

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

  /** Hash text the way the rest of the system hashes it (§8).
    *
    * The page cannot do this itself: `Hashing` is `MessageDigest`, and there is
    * no such thing in Scala.js. A second SHA-256 in the webview would have to
    * agree with the shell's and the JVM's byte for byte forever; asking the
    * shell means there is nothing new to keep in agreement. The shell's digest
    * is already pinned against the JVM's by `local-protocol/fixtures/content-hashes.json`.
    *
    * Pass text with its line ending ALREADY applied —
    * `Reconciler.storedWith(origin).applyTo(text)`. The convention is shared
    * Scala this page has; the shell hashes the bytes it is given and applies no
    * convention of its own.
    *
    * Fails outside the desktop shell rather than returning a wrong answer: a
    * hash nobody can compute has no sensible default, and a made-up one would
    * report a phantom conflict.
    */
  def hashText(storedText: String)(using ExecutionContext): Future[ContentHash] =
    if !available then Future.failed(IpcUnavailable)
    else
      invoke(HashText, js.Dynamic.literal(text = storedText)).map: value =>
        if js.typeOf(value) == "string" then ContentHash.fromHex(value.asInstanceOf[String])
        else throw IpcUnavailable

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
