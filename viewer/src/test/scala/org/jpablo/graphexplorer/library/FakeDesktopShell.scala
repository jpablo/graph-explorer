package org.jpablo.graphexplorer.library

import org.jpablo.graphexplorer.gxcore.model.LibraryEntry
import org.scalajs.dom

import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** A stand-in for the desktop shell, over an in-memory file system.
  *
  * `DesktopIpc` resolves `window.__TAURI__.core.invoke` per call, so a test
  * can give one. This fake answers the commands of the library and of the
  * files with the replies and the error codes that `main.rs` gives, so the
  * desktop library, its migrations and a viewer of a file run with no
  * desktop, no window and no disk.
  *
  * The hash is not SHA-256 (Scala.js has no digest), but it is a function of
  * the text, and it has 64 hex characters. A test computes the key of a path
  * with [[keyOf]], as the page does.
  */
final class FakeDesktopShell:

  /** The diagram files, by path. */
  val files: mutable.Map[String, String] = mutable.Map.empty

  /** `library/metadata/`, by entry file name. */
  val entries: mutable.Map[String, String] = mutable.Map.empty

  /** `library/diagrams/`, by record file name. */
  val legacy: mutable.Map[String, String] = mutable.Map.empty

  /** True when `library/metadata/` exists. */
  var migrated: Boolean = true

  /** The folder for new diagrams. */
  var folder: String = "/docs/Graph Explorer"

  /** The paths that `open_document` watches. */
  val watched: mutable.Set[String] = mutable.Set.empty

  /** True: `save_document` refuses a path that has no watch with
    * DOCUMENT_NOT_WATCHED, as `put_document_snapshot` in `main.rs` does.
    *
    * The default is false, because the older tests record a session with no
    * watch and then save.
    */
  var saveNeedsWatch: Boolean = false

  /** The open requests that wait for `complete_open`, by request id: the path,
    * true when this open made the watch, and the answer.
    */
  private val opens: mutable.Map[Long, (String, Boolean, Promise[FakeDesktopShell.OpenAnswer])] = mutable.Map.empty

  /** Each command, with its arguments, in order. */
  val calls: mutable.Buffer[(String, js.Dynamic)] = mutable.Buffer.empty

  /** A command that the fake refuses, with a code and a message. */
  val refusals: mutable.Map[String, (String, String)] = mutable.Map.empty

  /** The canonical spelling of a path, as `normalize_path` in `main.rs` gives
    * it. The commands of a file (`read_document`, `open_document`,
    * `create_document`, `rename_document`, `save_document`) find the file by
    * this spelling, and they give it back as the path of the file. `files`
    * holds each file under this spelling.
    *
    * The default changes nothing. A test can give a real rule of a machine,
    * for example `/tmp/` -> `/private/tmp/` on macOS.
    */
  var canonical: String => String = identity

  def hash(text: String): String =
    (0 until 8).map(i => f"${(s"$i:$text").hashCode & 0xffffffffL}%08x").mkString

  def keyOf(path: String): String = hash(LibraryEntry.keySource(path))

  def entryNameOf(path: String): String = s"${keyOf(path)}.json"

  def entryOf(path: String): Option[LibraryEntry] =
    entries.get(entryNameOf(path)).map(upickle.default.read[LibraryEntry](_))

  def putEntry(entry: LibraryEntry): Unit =
    entries(entryNameOf(entry.path)) = upickle.default.write(entry, indent = 2)

  def commands: List[String] = calls.toList.map(_._1)

  def callsOf(command: String): List[js.Dynamic] = calls.toList.collect { case (`command`, args) => args }

  private def str(args: js.Dynamic, name: String): String = args.selectDynamic(name).asInstanceOf[String]

  /** The base check of `library_write` (`write_library_entry` in `main.rs`).
    * No base: the write does not check. `{state: "absent"}`: the entry must
    * not exist. `{state: "present", json}`: the entry must have exactly that
    * text.
    */
  private def baseHolds(base: js.Dynamic, onDisk: Option[String]): Boolean =
    if js.isUndefined(base) || base == null then true
    else
      (base.state.asInstanceOf[String], onDisk) match
        case ("absent", None)        => true
        case ("present", Some(json)) => base.json.asInstanceOf[String] == json
        case _                       => false

  private def ok(value: js.Any): js.Promise[js.Any] = js.Promise.resolve[js.Any](value)

  private def refuse(code: String, message: String): js.Promise[js.Any] =
    js.Promise.reject(js.Dynamic.literal(code = code, message = message))

  private def snapshot(path: String, text: String): js.Dynamic =
    js.Dynamic.literal(path = path, text = text, format = "dot", revision = hash(text), timestampMs = 1)

  private def listing(files: mutable.Map[String, String]): js.Array[js.Any] =
    js.Array(files.toVector.sortBy(_._1).map((name, json) => js.Dynamic.literal(name = name, json = json): js.Any)*)

  private def namesInFolder: Vector[String] =
    files.keys.toVector
      .filter(_.startsWith(folder + "/"))
      .map(_.stripPrefix(folder + "/"))
      .filter(rest => !rest.contains("/") || (rest.startsWith("Recovered/") && rest.count(_ == '/') == 1))
      .sorted

  def install(): Unit =
    val invoke: js.Function2[String, js.Any, js.Promise[js.Any]] = (command, rawArgs) =>
      val a = rawArgs.asInstanceOf[js.Dynamic]
      calls += command -> a
      refusals.get(command) match
        case Some((code, message)) => refuse(code, message)
        case None                  => answer(command, a)
    js.Dynamic.global.window.__TAURI__ = js.Dynamic.literal(core = js.Dynamic.literal(invoke = invoke))

  def uninstall(): Unit = js.Dynamic.global.window.__TAURI__ = null

  /** `show_file` in `main.rs`, for a `gx open` of the file at `path`.
    *
    * The steps are the steps of the shell:
    *   1. `add_watch`. A new watch sends `ge:document.changed` with the text.
    *      For a watch that exists, `activate_document` sends the same event.
    *   2. `await_open` sends `ge:open.requested` and waits for `complete_open`.
    *   3. An answer that is not `displayed` goes to `rollback_failed_open`. It
    *      removes the watch only when this open made it, and only when the
    *      page does not hold the file (`failed_open_keeps_watch`: each code
    *      but PARSE_FAILED and ACTIVATION_REFUSED).
    *
    * The future completes when the page answers. The shell stops after
    * `OPEN_TIMEOUT` (45 s). This fake does not stop, so a test that expects an
    * answer must use its own time limit.
    */
  def show(path: String): Future[FakeDesktopShell.OpenAnswer] =
    val file = canonical(path)
    files.get(file) match
      case None =>
        Future.successful(
          FakeDesktopShell.OpenAnswer("failed", Some("WATCH_FAILED"), Some(s"no such file: $file"))
        )
      case Some(text) =>
        val created = !watched.contains(file)
        watched += file
        dispatch("ge:document.changed", js.Dynamic.literal(text = text, path = file, revision = hash(text)))
        val id     = FakeDesktopShell.nextRequestId()
        val answer = Promise[FakeDesktopShell.OpenAnswer]()
        opens(id) = (file, created, answer)
        dispatch(
          "ge:open.requested",
          js.Dynamic.literal(requestId = id.toDouble, target = js.Dynamic.literal(kind = "file", path = file))
        )
        answer.future

  /** The script that the shell evaluates in the page: one `CustomEvent`. */
  private def dispatch(name: String, detail: js.Any): Unit =
    val event = js.Dynamic.newInstance(js.Dynamic.global.CustomEvent)(name, js.Dynamic.literal(detail = detail))
    dom.window.dispatchEvent(event.asInstanceOf[dom.Event])

  /** `complete_open` and the end of `show_file` in `main.rs`. */
  private def completeOpen(a: js.Dynamic): Unit =
    val id = a.selectDynamic("requestId").asInstanceOf[Double].toLong
    opens.remove(id).foreach: (file, created, answer) =>
      val status = str(a, "status")
      val code   = optional(a, "code")
      // `rollback_failed_open`: only a watch that this open made goes, and
      // only for a file that the page does not hold.
      val pageHoldsFile = code.exists(FakeDesktopShell.codesThatKeepTheWatch.contains)
      if status != "displayed" && created && !pageHoldsFile then watched -= file
      answer.trySuccess(FakeDesktopShell.OpenAnswer(status, code, optional(a, "message")))

  private def optional(args: js.Dynamic, name: String): Option[String] =
    val value = args.selectDynamic(name)
    if js.isUndefined(value) || value == null then None else Some(value.asInstanceOf[String])

  private def answer(command: String, a: js.Dynamic): js.Promise[js.Any] =
    command match
      case "hash_text" => ok(hash(str(a, "text")))

      case "library_list" => ok(if migrated then listing(entries) else js.Array())

      case "library_write" =>
        val name = str(a, "name")
        if !migrated then refuse("LIBRARY_NOT_MIGRATED", "the metadata store does not exist")
        else if !baseHolds(a.selectDynamic("base"), entries.get(name)) then
          refuse("LIBRARY_CONFLICT", s"$name changed since the page read it")
        else
          entries(name) = str(a, "json")
          ok(())

      case "library_read" =>
        val name = str(a, "name")
        entries.get(name).fold(refuse("LIBRARY_ENTRY_MISSING", s"$name does not exist"))(json => ok(json))

      case "library_delete" => ok(entries.remove(str(a, "name")).isDefined)

      case "library_legacy_list" =>
        ok(js.Dynamic.literal(migrated = migrated, records = if migrated then js.Array() else listing(legacy)))

      case "library_commit_migration" =>
        if migrated then refuse("LIBRARY_ALREADY_MIGRATED", "metadata/ exists")
        else
          val items = a.selectDynamic("entries").asInstanceOf[js.Array[js.Dynamic]]
          items.foreach(item => entries(str(item, "name")) = str(item, "json"))
          migrated = true
          ok(js.Dynamic.literal(committed = items.length))

      case "diagrams_folder" =>
        ok(js.Dynamic.literal(path = folder, existing = js.Array(namesInFolder*)))

      case "read_document" =>
        val path = canonical(str(a, "path"))
        files.get(path).fold(refuse("DOCUMENT_MISSING", s"no such file: $path"))(text => ok(snapshot(path, text)))

      case "open_document" =>
        val path = canonical(str(a, "path"))
        files.get(path) match
          case None => refuse("WATCH_FAILED", s"watch target does not exist or is not accessible: $path")
          case Some(text) =>
            // `add_watch`: a new watch sends the document event of the file
            // before the reply. A page with no document listener gets nothing.
            if !watched.contains(path) then
              dispatch("ge:document.changed", js.Dynamic.literal(text = text, path = path, revision = hash(text)))
            watched += path
            ok(snapshot(path, text))

      case "create_document" =>
        val path = canonical(str(a, "path"))
        if files.contains(path) then refuse("DOCUMENT_EXISTS", s"$path exists")
        else
          files(path) = str(a, "text")
          ok(snapshot(path, files(path)))

      case "rename_document" =>
        val from = canonical(str(a, "from"))
        val to   = canonical(str(a, "to"))
        if !files.contains(from) then refuse("DOCUMENT_MISSING", s"no such file: $from")
        else if from == to then
          // `rename_file` in `main.rs`: when the two names are one file
          // (`from_norm == to_norm`), the rename does nothing and gives Ok
          // with the same path. It does not refuse with DOCUMENT_EXISTS.
          ok(js.Dynamic.literal(path = to, revision = hash(files(from)), watchMoved = watched.contains(from)))
        else if files.contains(to) then refuse("DOCUMENT_EXISTS", s"$to exists")
        else
          val text = files.remove(from).get
          files(to) = text
          val moved = watched.remove(from)
          if moved then watched += to
          ok(js.Dynamic.literal(path = to, revision = hash(text), watchMoved = moved))

      case "save_document" =>
        val path = canonical(str(a, "path"))
        files.get(path) match
          // `put_document_snapshot`: the registry of the watches has no entry
          // for the path. `save_document` gives this error the code
          // DOCUMENT_NOT_WATCHED.
          case _ if saveNeedsWatch && !watched.contains(path) =>
            refuse("DOCUMENT_NOT_WATCHED", s"path is not currently watched: $path")
          case Some(current) if hash(current) != str(a, "baseRevision") =>
            js.Promise.reject(
              js.Dynamic.literal(code = "DOCUMENT_CONFLICT", message = "changed", currentRevision = hash(current))
            )
          case _ =>
            files(path) = str(a, "text")
            ok(snapshot(path, files(path)))

      case "complete_open" =>
        completeOpen(a)
        ok(())

      case "viewer_ready" | "set_unsaved" | "live_view_reply" => ok(())

      case other => refuse("UNKNOWN_COMMAND", s"the fake shell has no command $other")

object FakeDesktopShell:

  /** `failed_open_keeps_watch` in `main.rs`: the refusals for a file that the
    * page holds.
    */
  val codesThatKeepTheWatch: Set[String] = Set("PARSE_FAILED", "ACTIVATION_REFUSED")

  /** The answer of the page to one open request, as `complete_open` gives it. */
  final case class OpenAnswer(status: String, code: Option[String], message: Option[String]) derives CanEqual

  /** One counter for all the fakes. A request that a page never answers stays
    * in the `pending` map of `DesktopOpenRequests` after its test. Thus an id
    * of a later fake must not be the same id.
    */
  private var lastRequestId = 0L

  private def nextRequestId(): Long =
    lastRequestId += 1
    lastRequestId

/** `assert` and `assertEquals`, with a message that reaches the log.
  *
  * In the Playwright runner, a failed assertion arrives with an EMPTY message,
  * so a failure does not say which check failed. The console does reach the
  * log, so each check prints its clue with the name of the spec first.
  */
trait LoggedChecks:
  self: munit.FunSuite =>

  def checkPrefix: String

  def check(condition: Boolean, clue: => String): Unit =
    if !condition then println(s"[$checkPrefix] $clue")
    assert(condition, clue)

  def same[A](obtained: A, expected: A, clue: => String = "the values differ")(using CanEqual[A, A]): Unit =
    check(obtained == expected, s"$clue\n  obtained: $obtained\n  expected: $expected")
