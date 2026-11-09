package org.jpablo.graphexplorer.viewer.desktop

import org.jpablo.graphexplorer.gxcore.model.{ContentHash, LibraryEntry}

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

  val OpenDocument           = "open_document"
  val SaveDocument           = "save_document"
  val ListDocuments          = "list_documents"
  val ReadDocument           = "read_document"
  val CreateDocument         = "create_document"
  val RenameDocument         = "rename_document"
  val DiagramsFolderCommand  = "diagrams_folder"
  val LibraryList            = "library_list"
  val LibraryWrite           = "library_write"
  val LibraryDelete          = "library_delete"
  val LibraryLegacyList      = "library_legacy_list"
  val LibraryCommitMigration = "library_commit_migration"

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

  /** A rejected command, with the code that the shell gave (`DOCUMENT_EXISTS`,
    * `DOCUMENT_MISSING`, ...). A caller can act on the code, and show the
    * message to the person.
    */
  final case class IpcFailure(code: String, message: String) extends Exception(s"$code: $message")

  /** A file as the shell read it: its canonical path, its text, and its
    * revision (the hex SHA-256 of its bytes).
    */
  final case class FileSnapshot(path: String, text: String, revision: String) derives CanEqual

  /** The folder for new diagrams (`$GX_DIAGRAMS_DIR`, or else
    * `~/Documents/Graph Explorer`), and the names of the files in it and in
    * its `Recovered/` subfolder.
    */
  final case class DiagramsFolder(path: String, existing: Vector[String]) derives CanEqual

  /** A file of the library directory. `content` is the JSON, or the reason
    * that the shell could not read the file.
    */
  final case class StoredFile(name: String, content: Either[String, String]) derives CanEqual

  /** Run a command, and change a rejection into an [[IpcFailure]]. */
  private def call(command: String, args: js.Any)(using ExecutionContext): Future[js.Any] =
    if !available then Future.failed(IpcUnavailable)
    else
      invoke(command, args).transform:
        case Success(value) => Success(value)
        case Failure(error) => Failure(failureOf(error))

  private def failureOf(error: Throwable): IpcFailure =
    val rejection: js.Any = error match
      case js.JavaScriptException(value) => value.asInstanceOf[js.Any]
      case other                         => other.getMessage
    if js.typeOf(rejection) == "object" && rejection != null then
      IpcFailure(
        asString(rejection, "code").getOrElse("IPC_FAILED"),
        asString(rejection, "message").getOrElse(String.valueOf(rejection))
      )
    else IpcFailure("IPC_FAILED", String.valueOf(rejection))

  private def snapshotOf(value: js.Any, path: String): FileSnapshot =
    FileSnapshot(
      path = asString(value, "path").getOrElse(path),
      text = asString(value, "text").getOrElse(""),
      revision = asString(value, "revision").getOrElse("")
    )

  /** Open a file for a view: the shell checks the policy, starts a watch, and
    * gives the bytes (`open_document`).
    *
    * The watch stays after the view closes. Its key is the PATH, and other
    * owners share it: `gx watch` can hold a watch on the same file. Thus a
    * page that removes the watch when it navigates can stop a watch that the
    * page did not start.
    */
  def openFile(path: String)(using ExecutionContext): Future[FileSnapshot] =
    call(OpenDocument, js.Dynamic.literal(path = path)).map(snapshotOf(_, path))

  /** Read a file with no watch (`read_document`). The home page uses it for
    * the title and the thumbnail of each diagram in the library.
    */
  def readFile(path: String)(using ExecutionContext): Future[FileSnapshot] =
    call(ReadDocument, js.Dynamic.literal(path = path)).map(snapshotOf(_, path))

  /** Create a NEW file (`create_document`). The shell refuses a file that
    * exists, with `DOCUMENT_EXISTS`.
    */
  def createFile(path: String, text: String)(using ExecutionContext): Future[FileSnapshot] =
    call(CreateDocument, js.Dynamic.literal(path = path, text = text)).map(snapshotOf(_, path))

  /** The new place of a renamed file. `watchMoved` tells that the shell moved
    * the watch of the file to the new path.
    */
  final case class Renamed(path: String, revision: String, watchMoved: Boolean) derives CanEqual

  /** Rename a file (`rename_document`). The shell refuses a name that exists,
    * with `DOCUMENT_EXISTS`.
    */
  def renameFile(from: String, to: String)(using ExecutionContext): Future[Renamed] =
    call(RenameDocument, js.Dynamic.literal(from = from, to = to)).map: value =>
      Renamed(
        path = asString(value, "path").getOrElse(to),
        revision = asString(value, "revision").getOrElse(""),
        watchMoved = field(value, "watchMoved").exists(v => js.typeOf(v) == "boolean" && v.asInstanceOf[Boolean])
      )

  def diagramsFolder()(using ExecutionContext): Future[DiagramsFolder] =
    call(DiagramsFolderCommand, js.Dynamic.literal()).map: value =>
      DiagramsFolder(
        path = asString(value, "path").getOrElse(""),
        existing = stringsIn(value, "existing")
      )

  /** The entries of the metadata store (`library_list`). */
  def libraryList()(using ExecutionContext): Future[Vector[StoredFile]] =
    call(LibraryList, js.Dynamic.literal()).map(storedFiles)

  /** Write one entry of the metadata store. The shell refuses a write before
    * the migration made the store, with `LIBRARY_NOT_MIGRATED`.
    */
  def libraryWrite(name: String, json: String)(using ExecutionContext): Future[Unit] =
    call(LibraryWrite, js.Dynamic.literal(name = name, json = json)).map(_ => ())

  /** Delete one entry of the metadata store. The diagram file does not change. */
  def libraryDelete(name: String)(using ExecutionContext): Future[Boolean] =
    call(LibraryDelete, js.Dynamic.literal(name = name)).map(v => js.typeOf(v) == "boolean" && v.asInstanceOf[Boolean])

  /** The old records of `library/diagrams/`, for the migration. `migrated` is
    * true when the metadata store exists. Then the records are empty.
    */
  def libraryLegacyList()(using ExecutionContext): Future[(Boolean, Vector[StoredFile])] =
    call(LibraryLegacyList, js.Dynamic.literal()).map: value =>
      val migrated = field(value, "migrated").exists(v => js.typeOf(v) == "boolean" && v.asInstanceOf[Boolean])
      (migrated, field(value, "records").map(storedFiles).getOrElse(Vector.empty))

  /** Make the metadata store with these entries, as one step. The shell writes
    * them into a temporary directory and renames it last.
    */
  def commitMigration(entries: Vector[(String, String)])(using ExecutionContext): Future[Unit] =
    val items = js.Array(entries.map((name, json) => js.Dynamic.literal(name = name, json = json))*)
    call(LibraryCommitMigration, js.Dynamic.literal(entries = items)).map(_ => ())

  /** The name of the entry file of a diagram: the hex SHA-256 of the
    * canonical `file:` URI of its path ([[LibraryEntry.keySource]]), and
    * `.json`. `canonicalPath` comes from the shell.
    */
  def entryNameOf(canonicalPath: String)(using ExecutionContext): Future[String] =
    hashText(LibraryEntry.keySource(canonicalPath)).map(hash => s"${hash.hex}.json")

  private def storedFiles(value: js.Any): Vector[StoredFile] =
    if !js.Array.isArray(value) then Vector.empty
    else
      value.asInstanceOf[js.Array[js.Any]].toVector.flatMap: item =>
        asString(item, "name").map: name =>
          StoredFile(name, asString(item, "json").toRight(asString(item, "error").getOrElse("the shell could not read it")))

  private def stringsIn(value: js.Any, name: String): Vector[String] =
    field(value, name) match
      case Some(array) if js.Array.isArray(array) =>
        array.asInstanceOf[js.Array[js.Any]].toVector.collect { case v if js.typeOf(v) == "string" => v.asInstanceOf[String] }
      case _ => Vector.empty

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
    * The shell hashes the bytes that it gets, and it applies no convention of
    * its own. The library key ([[entryNameOf]]) is the one user now.
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

  private[desktop] def field(value: js.Any, name: String): Option[js.Any] =
    if js.isUndefined(value) || value == null then None
    else
      val selected = value.asInstanceOf[js.Dynamic].selectDynamic(name)
      if js.isUndefined(selected) || selected == null then None else Some(selected)
