package org.jpablo.graphexplorer.library

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.*
import org.jpablo.graphexplorer.viewer.models.DiagramId

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

  private def diagram(id: String, name: String, text: String, tags: List[String] = Nil) =
    Diagram(
      id = DiagramId(id),
      format = "DOT",
      text = text,
      binding = None,
      metadata = DiagramMetadata(name = name, folder = FolderPath.root, tags = tags),
      createdAt = 1,
      updatedAt = 1
    )

  private def withShell[A](f: FakeShell => Future[A]): Future[A] =
    val shell = FakeShell()
    shell.install()
    f(shell).map { a => shell.uninstall(); a }

  test("the library the shell holds becomes the library the app reads"):
    withShell: shell =>
      shell.files("a.json") = upickle.default.write(diagram("a", "Alpha", "digraph { a }"))
      DesktopLibrary.load().map: loaded =>
        val library = DesktopLibrary(loaded)
        assertEquals(library.indexNow().diagrams.map(_.name), List("Alpha"))
        assert(library.diagramExists(DiagramId("a")))

  /** V-15. A diagram that a tool wrote while no desktop ran shows in the UI
    * when a desktop starts. D7.3 exists to give this property. The user found
    * that this property did not exist when the app showed nothing after
    * `gx import`.
    */
  test("V-15: a diagram gx imported while the app was closed is simply there"):
    withShell: shell =>
      shell.files("imported.json") =
        upickle.default.write(diagram("imported", "From gx", "digraph { gx }"))
      DesktopLibrary.load().map: loaded =>
        assertEquals(DesktopLibrary(loaded).indexNow().diagrams.map(_.name), List("From gx"))

  /** The same thing while the app is RUNNING, which is what the shell's
    * `ge:library.changed` is for.
    */
  test("V-15: a diagram gx imports while the app is open appears on refresh"):
    withShell: shell =>
      DesktopLibrary.load().flatMap: empty =>
        val library = DesktopLibrary(empty)
        assertEquals(library.indexNow().diagrams, Nil)
        // `gx import` adds a diagram, and the app does not know about it.
        shell.files("late.json") = upickle.default.write(diagram("late", "Late arrival", "digraph { l }"))
        library.refresh().map: _ =>
          assertEquals(library.indexNow().diagrams.map(_.name), List("Late arrival"))

  test("a diagram that will not parse is skipped, not fatal to the whole library"):
    withShell: shell =>
      shell.files("good.json") = upickle.default.write(diagram("good", "Good", "digraph { g }"))
      shell.files("bad.json") = "{ this is not a diagram"
      DesktopLibrary.load().map: loaded =>
        assertEquals(loaded.map(_.metadata.name), Vector("Good"))

  test("a UI save writes the diagram under the name the shared rule gives it"):
    withShell: shell =>
      shell.files("a.json") = upickle.default.write(diagram("a", "Alpha", "digraph { a }"))
      DesktopLibrary.load().map: loaded =>
        val library = DesktopLibrary(loaded)
        val state   = library.createDiagramPersistence(DiagramId("a"), None)
        state.update(_.copy(source = "digraph { a -> b }"))
        library.flush()
        assert(shell.writes.contains("a.json"), shell.writes.toString)
        val saved = upickle.default.read[Diagram](shell.files("a.json"))
        assertEquals(saved.text, "digraph { a -> b }")

  /** The compare-and-swap that matters in practice: a `gx run tag` landing
    * mid-edit must not be overwritten by the copy the page opened with.
    */
  test("a save merges onto the diagram as it stands now, not as it was opened"):
    withShell: shell =>
      shell.files("a.json") = upickle.default.write(diagram("a", "Alpha", "digraph { a }"))
      DesktopLibrary.load().flatMap: loaded =>
        val library = DesktopLibrary(loaded)
        val state   = library.createDiagramPersistence(DiagramId("a"), None)

        // `gx run a tag --params {"tags":["infra"]}` while the page is open.
        shell.files("a.json") =
          upickle.default.write(diagram("a", "Alpha", "digraph { a }", tags = List("infra")))
        library.refresh().map: _ =>
          state.update(_.copy(source = "digraph { a -> b }"))
          library.flush()
          val saved = upickle.default.read[Diagram](shell.files("a.json"))
          assertEquals(saved.text, "digraph { a -> b }", "the page's edit survives")
          assertEquals(saved.metadata.tags, List("infra"), "and so does the tag it never knew about")

  test("deleting removes the diagram from disk and from the mirror"):
    withShell: shell =>
      shell.files("a.json") = upickle.default.write(diagram("a", "Alpha", "digraph { a }"))
      DesktopLibrary.load().map: loaded =>
        val library = DesktopLibrary(loaded)
        library.deleteDiagram(DiagramId("a"))
        assert(!library.diagramExists(DiagramId("a")))
        assert(!shell.files.contains("a.json"), shell.files.keys.toString)

  test("opening a diagram and changing nothing writes nothing"):
    // Found by running it: the library sorts by `updatedAt`, so a write with no
    // edit behind it would reorder the library for having looked at a diagram.
    withShell: shell =>
      shell.files("a.json") = upickle.default.write(diagram("a", "Alpha", "digraph { a }"))
      DesktopLibrary.load().map: loaded =>
        val library = DesktopLibrary(loaded)
        val state   = library.createDiagramPersistence(DiagramId("a"), None)
        // Set the same value again. The page state "changes", but the diagram
        // does not change.
        state.set(state.now())
        library.flush()
        assertEquals(shell.writes.toList, Nil, s"no write expected, got ${shell.writes}")
