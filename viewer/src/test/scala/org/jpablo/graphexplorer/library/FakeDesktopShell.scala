package org.jpablo.graphexplorer.library

import org.jpablo.graphexplorer.gxcore.model.LibraryEntry

import scala.collection.mutable
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

  /** Each command, with its arguments, in order. */
  val calls: mutable.Buffer[(String, js.Dynamic)] = mutable.Buffer.empty

  /** A command that the fake refuses, with a code and a message. */
  val refusals: mutable.Map[String, (String, String)] = mutable.Map.empty

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

  private def answer(command: String, a: js.Dynamic): js.Promise[js.Any] =
    command match
      case "hash_text" => ok(hash(str(a, "text")))

      case "library_list" => ok(if migrated then listing(entries) else js.Array())

      case "library_write" =>
        if !migrated then refuse("LIBRARY_NOT_MIGRATED", "the metadata store does not exist")
        else
          entries(str(a, "name")) = str(a, "json")
          ok(())

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
        val path = str(a, "path")
        files.get(path).fold(refuse("DOCUMENT_MISSING", s"no such file: $path"))(text => ok(snapshot(path, text)))

      case "open_document" =>
        val path = str(a, "path")
        files.get(path) match
          case None => refuse("WATCH_FAILED", s"watch target does not exist or is not accessible: $path")
          case Some(text) =>
            watched += path
            ok(snapshot(path, text))

      case "create_document" =>
        val path = str(a, "path")
        if files.contains(path) then refuse("DOCUMENT_EXISTS", s"$path exists")
        else
          files(path) = str(a, "text")
          ok(snapshot(path, files(path)))

      case "rename_document" =>
        val from = str(a, "from")
        val to   = str(a, "to")
        if !files.contains(from) then refuse("DOCUMENT_MISSING", s"no such file: $from")
        else if files.contains(to) then refuse("DOCUMENT_EXISTS", s"$to exists")
        else
          val text = files.remove(from).get
          files(to) = text
          val moved = watched.remove(from)
          if moved then watched += to
          ok(js.Dynamic.literal(path = to, revision = hash(text), watchMoved = moved))

      case "save_document" =>
        val path = str(a, "path")
        files.get(path) match
          case Some(current) if hash(current) != str(a, "baseRevision") =>
            js.Promise.reject(
              js.Dynamic.literal(code = "DOCUMENT_CONFLICT", message = "changed", currentRevision = hash(current))
            )
          case _ =>
            files(path) = str(a, "text")
            ok(snapshot(path, files(path)))

      case "viewer_ready" | "complete_open" | "set_unsaved" | "live_view_reply" => ok(())

      case other => refuse("UNKNOWN_COMMAND", s"the fake shell has no command $other")

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
