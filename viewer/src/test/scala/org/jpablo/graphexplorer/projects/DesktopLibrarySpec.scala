package org.jpablo.graphexplorer.projects

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.*
import org.jpablo.graphexplorer.viewer.state.ProjectId

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** The desktop library, driven against a stand-in for the shell.
  *
  * `DesktopIpc` resolves `window.__TAURI__.core.invoke` per call, so a test can
  * supply one — which means the mirror, the merge and D7.3's live update are
  * all reachable without a desktop, a window, or a disk.
  */
class DesktopLibrarySpec extends FunSuite:

  /** A stand-in for the shell's four library commands, over a map. */
  private class FakeShell:
    val files: mutable.Map[String, String] = mutable.Map.empty
    val writes: mutable.Buffer[String]     = mutable.Buffer.empty

    def install(): Unit =
      val invoke: js.Function2[String, js.Any, js.Promise[js.Any]] = (command, args) =>
        val a = args.asInstanceOf[js.Dynamic]
        command match
          case "library_list" =>
            js.Promise.resolve[js.Any](
              js.Array(files.toVector.sortBy(_._1).map { (name, json) =>
                js.Dynamic.literal(name = name, json = json)
              }*)
            )
          case "library_write" =>
            val name = a.selectDynamic("name").asInstanceOf[String]
            files(name) = a.selectDynamic("json").asInstanceOf[String]
            writes += name
            js.Promise.resolve[js.Any](())
          case "library_delete" =>
            files.remove(a.selectDynamic("name").asInstanceOf[String])
            js.Promise.resolve[js.Any](true)
          case other =>
            js.Promise.reject(js.JavaScriptException(s"unexpected command $other"))

      js.Dynamic.global.window.__TAURI__ =
        js.Dynamic.literal(core = js.Dynamic.literal(invoke = invoke))

    def uninstall(): Unit =
      js.Dynamic.global.window.__TAURI__ = null

  private def record(id: String, name: String, text: String, tags: List[String] = Nil) =
    Diagram(
      id = DiagramId(id),
      name = name,
      folder = FolderPath.root,
      format = "DOT",
      text = text,
      binding = None,
      metadata = DiagramMetadata(tags = tags),
      createdAt = 1,
      updatedAt = 1
    )

  private def withShell[A](f: FakeShell => Future[A]): Future[A] =
    val shell = FakeShell()
    shell.install()
    f(shell).map { a => shell.uninstall(); a }

  test("the library the shell holds becomes the library the app reads"):
    withShell: shell =>
      shell.files("a.json") = upickle.default.write(record("a", "Alpha", "digraph { a }"))
      DesktopLibrary.load().map: loaded =>
        val library = DesktopLibrary(loaded)
        assertEquals(library.directoryNow().projects.map(_.name), List("Alpha"))
        assert(library.projectExists(ProjectId("a")))

  /** V-15. A record written with no desktop running is reflected in the UI once
    * a desktop is there — the property D7.3 exists to provide, and the one the
    * user hit when `gx import` left the app showing nothing.
    */
  test("V-15: a diagram gx imported while the app was closed is simply there"):
    withShell: shell =>
      shell.files("imported.json") =
        upickle.default.write(record("imported", "From gx", "digraph { gx }"))
      DesktopLibrary.load().map: loaded =>
        assertEquals(DesktopLibrary(loaded).directoryNow().projects.map(_.name), List("From gx"))

  /** The same thing while the app is RUNNING, which is what the shell's
    * `ge:library.changed` is for.
    */
  test("V-15: a diagram gx imports while the app is open appears on refresh"):
    withShell: shell =>
      DesktopLibrary.load().flatMap: empty =>
        val library = DesktopLibrary(empty)
        assertEquals(library.directoryNow().projects, Nil)
        // `gx import` lands a record behind the app's back.
        shell.files("late.json") = upickle.default.write(record("late", "Late arrival", "digraph { l }"))
        library.refresh().map: _ =>
          assertEquals(library.directoryNow().projects.map(_.name), List("Late arrival"))

  test("a record that will not parse is skipped, not fatal to the whole library"):
    withShell: shell =>
      shell.files("good.json") = upickle.default.write(record("good", "Good", "digraph { g }"))
      shell.files("bad.json") = "{ this is not a diagram"
      DesktopLibrary.load().map: loaded =>
        assertEquals(loaded.map(_.name), Vector("Good"))

  test("a UI save writes the record under the name the shared rule gives it"):
    withShell: shell =>
      shell.files("a.json") = upickle.default.write(record("a", "Alpha", "digraph { a }"))
      DesktopLibrary.load().map: loaded =>
        val library = DesktopLibrary(loaded)
        val state   = library.createProjectPersistence(ProjectId("a"), None)
        state.update(_.copy(source = "digraph { a -> b }"))
        library.flush()
        assert(shell.writes.contains("a.json"), shell.writes.toString)
        val saved = upickle.default.read[Diagram](shell.files("a.json"))
        assertEquals(saved.text, "digraph { a -> b }")

  /** The compare-and-swap that matters in practice: a `gx run tag` landing
    * mid-edit must not be overwritten by the copy the page opened with.
    */
  test("a save merges onto the record as it stands now, not as it was opened"):
    withShell: shell =>
      shell.files("a.json") = upickle.default.write(record("a", "Alpha", "digraph { a }"))
      DesktopLibrary.load().flatMap: loaded =>
        val library = DesktopLibrary(loaded)
        val state   = library.createProjectPersistence(ProjectId("a"), None)

        // `gx run a tag --params {"tags":["infra"]}` while the page is open.
        shell.files("a.json") =
          upickle.default.write(record("a", "Alpha", "digraph { a }", tags = List("infra")))
        library.refresh().map: _ =>
          state.update(_.copy(source = "digraph { a -> b }"))
          library.flush()
          val saved = upickle.default.read[Diagram](shell.files("a.json"))
          assertEquals(saved.text, "digraph { a -> b }", "the page's edit survives")
          assertEquals(saved.metadata.tags, List("infra"), "and so does the tag it never knew about")

  test("deleting removes the record from disk and from the mirror"):
    withShell: shell =>
      shell.files("a.json") = upickle.default.write(record("a", "Alpha", "digraph { a }"))
      DesktopLibrary.load().map: loaded =>
        val library = DesktopLibrary(loaded)
        library.deleteProject(ProjectId("a"))
        assert(!library.projectExists(ProjectId("a")))
        assert(!shell.files.contains("a.json"), shell.files.keys.toString)
