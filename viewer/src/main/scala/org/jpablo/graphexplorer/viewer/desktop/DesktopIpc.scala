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

/** The webview side of the desktop boundary (architecture D3).
  *
  * Before, the page got the `port` and the `token` of the control server with
  * each `document.changed` event. The page then used a cross-origin `fetch`
  * with these credentials to reach the desktop. Thus the ONE principal that
  * shows content that the user did not write held a bearer token for a general
  * HTTP API. That content can be a `.dot` file from any location, or an
  * imported diagram.
  *
  * Now the page calls named Tauri commands, which are the commands below. This
  * is not a sandbox, because the page JS can still call them. But the
  * capability is *enumerated and revocable*, not *ambient*. Also, the JS heap
  * holds no credential that an attacker can steal.
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

  /** Start a watch on the origin of a diagram, so that a change to the origin
    * reaches the page.
    *
    * Phase 3 did NOT have this part. Reconciliation runs when the shell
    * reports a change. The shell reports a change only for a file that it
    * watches, but the page did not ask for a watch. The code defined, handled
    * and documented `open_document`, but no code called it. Thus an edit to an
    * origin reached the app only when `gx open` had a watch on the file. When
    * a person opened the same diagram from the library, the app did not see
    * the edit.
    *
    * The code DISCARDS the reply on purpose, although the reply contains the
    * bytes. If the code uses the bytes here, the text of the file goes on the
    * screen, and the diagram does not make a decision. §8 removed that
    * behaviour. `add_watch` sends a document event for each watch that it
    * creates, so the text arrives by the one route that reconciles it. Thus a
    * file that changed while the app was closed reconciles when the app opens it.
    *
    * CAUTION: there is no unwatch that matches this call. Do not add one
    * without a reference count. The key of a watch is the PATH, and watches are
    * shared. `gx watch` can hold a watch on the same file. If a page removes
    * the watch when it navigates, it stops a watch that the page did not start.
    */
  def openDocument(path: String)(using ExecutionContext): Future[Unit] =
    if !available then Future.unit
    else
      invoke(OpenDocument, js.Dynamic.literal(path = path)).map(_ => ()).recover:
        case error =>
          // A watch that the shell refused does not stop the view. The
          // diagram still opens, but it does not get the changes to the file.
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
